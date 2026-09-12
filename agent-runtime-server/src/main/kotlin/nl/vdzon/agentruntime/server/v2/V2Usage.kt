package nl.vdzon.agentruntime.server.v2

import nl.vdzon.agentruntime.contracts.v2.*
import nl.vdzon.agentruntime.server.config.ApiException
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class UsageFact(
    val tenantId:String,val jobId:String,val attemptId:String,val vendorId:String,val model:String,val mode:ExecutionMode,
    val taskType:TaskType,val metric:UsageMetric?,val quantity:BigDecimal?,val unit:UsageUnit?,val quality:UsageQuality,
    val costKind:CostKind?,val costStatus:CostStatus?,val amount:BigDecimal?,val currency:String?,val startedAt:Instant,
)

@Repository
class V2UsageStore(private val jdbc:JdbcTemplate) {
    @Transactional
    fun append(job:StoredV2Job,attemptId:String,request:AppendUsageRequest) {
        request.metrics.forEach { metric ->
            val quantity=decimal(metric.quantity,"quantity")
            if(quantity<BigDecimal.ZERO) throw ApiException("INVALID_USAGE","Usage quantity cannot be negative.")
            try {
                jdbc.update("""INSERT INTO runtime_v2_usage(job_id,attempt_id,external_event_id,metric,quantity,unit,source,observed_at,created_at)
                    VALUES (?,?,?,?,?,?,?,?,?)""",job.view.id,attemptId,request.eventId,metric.metric.name,quantity,metric.unit.name,request.source.name,V2JobStore.utc(request.observedAt),V2JobStore.utc(Instant.now()))
            } catch(_:DuplicateKeyException) { return@forEach }
            calculateCost(job,attemptId,request.eventId,metric,request.observedAt)
        }
        val quality=when(request.source){UsageSource.MOCK->UsageQuality.MOCK;UsageSource.PROVIDER_REPORTED->UsageQuality.COMPLETE;UsageSource.WORKER_MEASURED->UsageQuality.PARTIAL}
        jdbc.update("UPDATE runtime_v2_attempt SET usage_quality=?,provider_request_id=COALESCE(?,provider_request_id) WHERE id=?",quality.name,request.providerRequestId,attemptId)
    }

    private fun calculateCost(job:StoredV2Job,attemptId:String,eventId:String,metric:UsageMetricValue,observedAt:Instant) {
        if (job.view.execution.mode == ExecutionMode.MOCK) return
        val rateMode = if (job.view.execution.mode == ExecutionMode.SUBSCRIPTION) ExecutionMode.API else job.view.execution.mode
        val rates=jdbc.query("""SELECT * FROM runtime_v2_price_rate WHERE vendor_id=? AND model=? AND execution_mode=? AND task_type=? AND metric=?
            AND valid_from<=? AND (valid_until IS NULL OR valid_until>?) ORDER BY version_number DESC LIMIT 1""",{rs,_ ->
            Triple(rs.getString("id"),rs.getBigDecimal("unit_size"),Pair(rs.getBigDecimal("unit_price"),rs.getString("currency")))
        },job.view.execution.vendorId,job.view.execution.model,rateMode.name,job.view.taskType.name,metric.metric.name,V2JobStore.utc(observedAt),V2JobStore.utc(observedAt))
        rates.firstOrNull()?.let{rate->
            val usageId=jdbc.queryForObject("SELECT id FROM runtime_v2_usage WHERE attempt_id=? AND external_event_id=? AND metric=?",Long::class.java,attemptId,eventId,metric.metric.name)
            val amount=decimal(metric.quantity,"quantity").divide(rate.second,12,RoundingMode.HALF_UP).multiply(rate.third.first)
            val costKind = if (job.view.execution.mode == ExecutionMode.SUBSCRIPTION) CostKind.API_EQUIVALENT else CostKind.CALCULATED
            jdbc.update("""INSERT INTO runtime_v2_cost(job_id,attempt_id,usage_id,price_rate_id,cost_kind,cost_status,amount,currency,created_at)
                VALUES (?,?,?,?,?,'ESTIMATED',?,?,?)""",job.view.id,attemptId,usageId,rate.first,costKind.name,amount,rate.third.second,V2JobStore.utc(Instant.now()))
        }
    }

    fun attemptSummary(attemptId:String):JobUsageSummary {
        val attempt=jdbc.query("SELECT attempt_number,usage_quality FROM runtime_v2_attempt WHERE id=?",{rs,_->rs.getInt(1) to UsageQuality.valueOf(rs.getString(2))},attemptId).firstOrNull() ?: return JobUsageSummary(0,UsageQuality.UNAVAILABLE)
        val metrics=jdbc.query("SELECT metric,unit,SUM(quantity) quantity FROM runtime_v2_usage WHERE attempt_id=? GROUP BY metric,unit",{rs,_->UsageMetricValue(UsageMetric.valueOf(rs.getString("metric")),rs.getBigDecimal("quantity").stripTrailingZeros().toPlainString(),UsageUnit.valueOf(rs.getString("unit")))},attemptId)
        val costs=jdbc.query("SELECT cost_kind,cost_status,currency,SUM(amount) amount FROM runtime_v2_cost WHERE attempt_id=? GROUP BY cost_kind,cost_status,currency",{rs,_->CostValue(CostKind.valueOf(rs.getString("cost_kind")),CostStatus.valueOf(rs.getString("cost_status")),rs.getBigDecimal("amount").setScale(6,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(),rs.getString("currency"))},attemptId)
        return JobUsageSummary(attempt.first,attempt.second,metrics,costs)
    }

    fun jobSummary(jobId:String):JobUsageSummary {
        val attempts=jdbc.queryForObject("SELECT COUNT(*) FROM runtime_v2_attempt WHERE job_id=?",Int::class.java,jobId)?:0
        val qualities=jdbc.query("SELECT usage_quality FROM runtime_v2_attempt WHERE job_id=?",{rs,_->UsageQuality.valueOf(rs.getString(1))},jobId)
        val quality=when { qualities.isEmpty()->UsageQuality.UNAVAILABLE;qualities.all{it==UsageQuality.COMPLETE}->UsageQuality.COMPLETE;qualities.all{it==UsageQuality.MOCK}->UsageQuality.MOCK;qualities.all{it==UsageQuality.UNAVAILABLE}->UsageQuality.UNAVAILABLE;else->UsageQuality.PARTIAL }
        val metrics=jdbc.query("SELECT metric,unit,SUM(quantity) quantity FROM runtime_v2_usage WHERE job_id=? GROUP BY metric,unit",{rs,_->UsageMetricValue(UsageMetric.valueOf(rs.getString("metric")),rs.getBigDecimal("quantity").stripTrailingZeros().toPlainString(),UsageUnit.valueOf(rs.getString("unit")))},jobId)
        val costs=jdbc.query("SELECT cost_kind,cost_status,currency,SUM(amount) amount FROM runtime_v2_cost WHERE job_id=? GROUP BY cost_kind,cost_status,currency",{rs,_->CostValue(CostKind.valueOf(rs.getString("cost_kind")),CostStatus.valueOf(rs.getString("cost_status")),rs.getBigDecimal("amount").setScale(6,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(),rs.getString("currency"))},jobId).toMutableList()
        allocatedCost(jobId)?.let(costs::add)
        return JobUsageSummary(attempts,quality,metrics,costs)
    }

    fun facts(from:Instant,until:Instant,tenantId:String?):List<UsageFact> {
        val tenantClause=if(tenantId==null)"" else " AND j.tenant_id=?"
        val args=mutableListOf<Any>(V2JobStore.utc(from),V2JobStore.utc(until));tenantId?.let{args+=it}
        return jdbc.query("""SELECT j.tenant_id,j.id job_id,a.id attempt_id,j.vendor_id,j.model,j.execution_mode,j.task_type,a.usage_quality,
            u.metric,u.quantity,u.unit,c.cost_kind,c.cost_status,c.amount,c.currency,a.started_at
            FROM runtime_v2_attempt a JOIN runtime_v2_job j ON j.id=a.job_id
            LEFT JOIN runtime_v2_usage u ON u.attempt_id=a.id LEFT JOIN runtime_v2_cost c ON c.usage_id=u.id
            WHERE a.started_at>=? AND a.started_at<?$tenantClause""",{rs,_->UsageFact(rs.getString("tenant_id"),rs.getString("job_id"),rs.getString("attempt_id"),rs.getString("vendor_id"),rs.getString("model"),ExecutionMode.valueOf(rs.getString("execution_mode")),TaskType.valueOf(rs.getString("task_type")),rs.getString("metric")?.let(UsageMetric::valueOf),rs.getBigDecimal("quantity"),rs.getString("unit")?.let(UsageUnit::valueOf),UsageQuality.valueOf(rs.getString("usage_quality")),rs.getString("cost_kind")?.let(CostKind::valueOf),rs.getString("cost_status")?.let(CostStatus::valueOf),rs.getBigDecimal("amount"),rs.getString("currency"),instant(rs.getObject("started_at")))},*args.toTypedArray())
    }

    fun createPrice(request:CreatePriceRateRequest):PriceRateView {
        val unitSize=decimal(request.unitSize,"unitSize");val price=decimal(request.unitPrice,"unitPrice")
        if(unitSize<=BigDecimal.ZERO||price<BigDecimal.ZERO||!request.currency.matches(Regex("[A-Z]{3}"))) throw ApiException("INVALID_PRICE","Invalid price rate.")
        val version=(jdbc.queryForObject("SELECT COALESCE(MAX(version_number),0) FROM runtime_v2_price_rate WHERE vendor_id=? AND model=? AND execution_mode=? AND task_type=? AND metric=?",Int::class.java,request.vendorId,request.model,request.mode.name,request.taskType.name,request.metric.name)?:0)+1
        val id=UUID.randomUUID().toString();val now=Instant.now()
        jdbc.update("""INSERT INTO runtime_v2_price_rate(id,version_number,vendor_id,model,execution_mode,task_type,metric,unit_size,unit_price,currency,valid_from,valid_until,source_reference,created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",id,version,request.vendorId,request.model,request.mode.name,request.taskType.name,request.metric.name,unitSize,price,request.currency,V2JobStore.utc(request.validFrom),request.validUntil?.let(V2JobStore::utc),request.sourceReference,V2JobStore.utc(now))
        return PriceRateView(id,version,request.vendorId,request.model,request.mode,request.taskType,request.metric,unitSize.toPlainString(),price.toPlainString(),request.currency,request.validFrom,request.validUntil,request.sourceReference,now)
    }

    fun prices(vendorId:String?,model:String?):List<PriceRateView> { val clauses=mutableListOf<String>();val args=mutableListOf<Any>();vendorId?.let{clauses+="vendor_id=?";args+=it};model?.let{clauses+="model=?";args+=it};val where=if(clauses.isEmpty())"" else " WHERE ${clauses.joinToString(" AND ")}"
        return jdbc.query("SELECT * FROM runtime_v2_price_rate$where ORDER BY valid_from DESC,version_number DESC",{rs,_->PriceRateView(rs.getString("id"),rs.getInt("version_number"),rs.getString("vendor_id"),rs.getString("model"),ExecutionMode.valueOf(rs.getString("execution_mode")),TaskType.valueOf(rs.getString("task_type")),UsageMetric.valueOf(rs.getString("metric")),rs.getBigDecimal("unit_size").toPlainString(),rs.getBigDecimal("unit_price").toPlainString(),rs.getString("currency"),instant(rs.getObject("valid_from")),rs.getObject("valid_until")?.let(::instant),rs.getString("source_reference"),instant(rs.getObject("created_at")))},*args.toTypedArray()) }

    fun createSubscription(request:CreateSubscriptionPeriodRequest):SubscriptionPeriodView {
        if(!request.periodEnd.isAfter(request.periodStart))throw ApiException("INVALID_PERIOD","periodEnd must be after periodStart.")
        val amount=decimal(request.amount,"amount");if(amount<BigDecimal.ZERO||!request.currency.matches(Regex("[A-Z]{3}")))throw ApiException("INVALID_SUBSCRIPTION","Invalid subscription amount.")
        val id=UUID.randomUUID().toString();val now=Instant.now()
        try{jdbc.update("""INSERT INTO runtime_v2_subscription_period(id,vendor_id,model,period_start,period_end,amount,currency,allocation_method,status,created_at)
            VALUES (?,?,?,?,?,?,?,?,'OPEN',?)""",id,request.vendorId,request.model,request.periodStart,request.periodEnd,amount,request.currency,request.allocationMethod.name,V2JobStore.utc(now))}catch(_:DuplicateKeyException){throw ApiException("SUBSCRIPTION_CONFLICT","A subscription period already exists.",HttpStatus.CONFLICT)}
        return SubscriptionPeriodView(id,request.vendorId,request.model,request.periodStart,request.periodEnd,amount.toPlainString(),request.currency,request.allocationMethod,SubscriptionStatus.OPEN,now)
    }
    fun subscriptions():List<SubscriptionPeriodView> = jdbc.query("SELECT * FROM runtime_v2_subscription_period ORDER BY period_start DESC",{rs,_->SubscriptionPeriodView(rs.getString("id"),rs.getString("vendor_id"),rs.getString("model"),rs.getObject("period_start",LocalDate::class.java),rs.getObject("period_end",LocalDate::class.java),rs.getBigDecimal("amount").toPlainString(),rs.getString("currency"),AllocationMethod.valueOf(rs.getString("allocation_method")),SubscriptionStatus.valueOf(rs.getString("status")),instant(rs.getObject("created_at")))})

    fun allocatedCost(jobId:String):CostValue? {
        val job=jdbc.query("SELECT vendor_id,model,execution_mode,created_at FROM runtime_v2_job WHERE id=?",{rs,_->listOf(rs.getString("vendor_id"),rs.getString("model"),rs.getString("execution_mode"),instant(rs.getObject("created_at")))},jobId).firstOrNull()?:return null
        if(job[2]!=ExecutionMode.SUBSCRIPTION.name)return null
        val at=job[3] as Instant;val date=at.atZone(ZoneOffset.UTC).toLocalDate()
        val period=subscriptions().firstOrNull{it.vendorId==job[0]&&it.model==job[1]&&!date.isBefore(it.periodStart)&&date.isBefore(it.periodEnd)&&it.allocationMethod!=AllocationMethod.NONE}?:return null
        val periodFacts=facts(period.periodStart.atStartOfDay(ZoneOffset.UTC).toInstant(),period.periodEnd.atStartOfDay(ZoneOffset.UTC).toInstant(),null).filter{it.vendorId==job[0]&&it.model==job[1]&&it.metric!=null&&it.quantity!=null}
        val total=periodFacts.sumOf{weight(it)};val own=periodFacts.filter{it.jobId==jobId}.sumOf{weight(it)}
        if(total.signum()==0)return null
        val amount=BigDecimal(period.amount).multiply(own).divide(total,6,RoundingMode.HALF_UP)
        return CostValue(CostKind.ALLOCATED,CostStatus.ESTIMATED,amount.stripTrailingZeros().toPlainString(),period.currency)
    }

    fun weight(fact:UsageFact):BigDecimal = when(fact.metric){UsageMetric.INPUT_TOKENS,UsageMetric.CACHED_INPUT_TOKENS,UsageMetric.OUTPUT_TOKENS,UsageMetric.REASONING_TOKENS->fact.quantity?:BigDecimal.ZERO;else->BigDecimal.ZERO}

    companion object { fun decimal(value:String,name:String)=runCatching{BigDecimal(value)}.getOrElse{throw ApiException("INVALID_DECIMAL","$name is not a decimal value.")};fun instant(value:Any):Instant=when(value){is OffsetDateTime->value.toInstant();is java.sql.Timestamp->value.toInstant();else->error("Unsupported timestamp")}}
}

@Service
class V2UsageService(private val store:V2UsageStore) {
    fun summary(from:Instant,until:Instant,tenantId:String?,groupBy:Set<String>,filters:Map<String,String?>):UsageSummaryResponse {
        if(!until.isAfter(from))throw ApiException("INVALID_PERIOD","until must be after from.")
        val allowed=setOf("TENANT","VENDOR","MODEL","MODE","TASK_TYPE");if(!allowed.containsAll(groupBy))throw ApiException("INVALID_GROUP_BY","Supported values: ${allowed.joinToString()}.")
        val allFacts=store.facts(from,until,null)
        val filtered=(tenantId?.let{tenant->allFacts.filter{it.tenantId==tenant}}?:allFacts).filter{f->filters["tenantId"]?.let{f.tenantId==it}?:true}.filter{f->filters["vendorId"]?.let{f.vendorId==it}?:true}.filter{f->filters["model"]?.let{f.model==it}?:true}.filter{f->filters["mode"]?.let{f.mode.name==it}?:true}.filter{f->filters["taskType"]?.let{f.taskType.name==it}?:true}
        fun dimensions(f:UsageFact)=buildMap<String,String>{if("TENANT" in groupBy)put("tenantId",f.tenantId);if("VENDOR" in groupBy)put("vendorId",f.vendorId);if("MODEL" in groupBy)put("model",f.model);if("MODE" in groupBy)put("mode",f.mode.name);if("TASK_TYPE" in groupBy)put("taskType",f.taskType.name)}
        val totals=filtered.filter{it.metric!=null&&it.quantity!=null}.groupBy{Triple(it.vendorId,it.model,Pair(it.mode,it.metric!!))}.mapValues{(_,v)->v.mapNotNull{it.quantity}.fold(BigDecimal.ZERO,BigDecimal::add)}
        val rows=filtered.groupBy(::dimensions).map{(dims,facts)->
            val metrics=facts.filter{it.metric!=null&&it.quantity!=null}.groupBy{it.metric!! to it.unit!!}.map{(key,items)->UsageMetricValue(key.first,items.mapNotNull{it.quantity}.fold(BigDecimal.ZERO,BigDecimal::add).stripTrailingZeros().toPlainString(),key.second)}
            val shares=metrics.mapNotNull{metric->val sample=facts.firstOrNull{it.metric==metric.metric}?:return@mapNotNull null;val total=totals[Triple(sample.vendorId,sample.model,Pair(sample.mode,metric.metric))]?:BigDecimal.ZERO;if(total.signum()==0)null else UsageShareValue(metric.metric,BigDecimal(metric.quantity).multiply(BigDecimal(100)).divide(total,4,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString())}
            val costs=facts.filter{it.amount!=null}.groupBy{Triple(it.costKind!!,it.costStatus!!,it.currency!!)}.map{(key,items)->CostValue(key.first,key.second,items.mapNotNull{it.amount}.fold(BigDecimal.ZERO,BigDecimal::add).setScale(6,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(),key.third)}.toMutableList()
            store.subscriptions().filter{period->period.allocationMethod!=AllocationMethod.NONE&&facts.any{it.vendorId==period.vendorId&&it.model==period.model&&it.mode==ExecutionMode.SUBSCRIPTION}}
                .groupBy{it.currency}.forEach{(currency,periods)->
                    val allocated=periods.fold(BigDecimal.ZERO){sum,period->val matchingAll=allFacts.filter{it.vendorId==period.vendorId&&it.model==period.model&&it.mode==ExecutionMode.SUBSCRIPTION&&it.startedAt.atZone(ZoneOffset.UTC).toLocalDate().let{date->!date.isBefore(period.periodStart)&&date.isBefore(period.periodEnd)}};val total=matchingAll.sumOf(store::weight);val own=facts.filter{it.vendorId==period.vendorId&&it.model==period.model}.sumOf(store::weight);if(total.signum()==0)sum else sum+BigDecimal(period.amount).multiply(own).divide(total,6,RoundingMode.HALF_UP)}
                    if(allocated.signum()>0)costs+=CostValue(CostKind.ALLOCATED,CostStatus.ESTIMATED,allocated.stripTrailingZeros().toPlainString(),currency)
                }
            UsageSummaryRow(dims,facts.map{it.jobId}.distinct().size.toLong(),facts.map{it.attemptId}.distinct().size.toLong(),facts.filter{it.quality==UsageQuality.UNAVAILABLE}.map{it.attemptId}.distinct().size.toLong(),metrics,costs,shares)
        }.sortedBy{it.dimensions.values.joinToString("|")}
        return UsageSummaryResponse(from,until,rows)
    }
}
