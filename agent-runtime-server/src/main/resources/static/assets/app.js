const statuses=['QUEUED','WAITING_FOR_WORKER','RUNNING','SUCCEEDED','FAILED','CANCELLED'];
const token=()=>sessionStorage.getItem('ar-token')||'';
const headers=()=>({Authorization:`Bearer ${token()}`});
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
statuses.forEach(s=>status.insertAdjacentHTML('beforeend',`<option>${s}</option>`));
auth.onclick=()=>{document.querySelector('#token').value=token();login.showModal()};
save.onclick=()=>sessionStorage.setItem('ar-token',document.querySelector('#token').value);
async function configureLogin(){
  try{
    const config=await fetch('/v1/auth/config').then(x=>x.json());
    if(!config.googleEnabled)return;
    const wait=()=>window.google?.accounts?.id?Promise.resolve():new Promise(r=>setTimeout(()=>r(wait()),100));
    await wait();
    google.accounts.id.initialize({client_id:config.googleClientId,callback:async response=>{
      const login=await fetch('/v1/auth/google',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({idToken:response.credential})});
      if(!login.ok){document.querySelector('#login-help').textContent='Dit Google-account heeft geen beheerrechten.';return}
      const body=await login.json();sessionStorage.setItem('ar-token',body.token);document.querySelector('#login').close();load();
    }});
    google.accounts.id.renderButton(document.querySelector('#google-login'),{theme:'outline',size:'large',text:'signin_with'});
  }catch(e){}
}
async function load(){
  if(!token()){login.showModal();live.textContent='Token nodig';return}
  try{
    const [sum,j,w]=await Promise.all([fetch('/v1/admin/summary',{headers:headers()}),fetch(`/v1/jobs${status.value?`?status=${status.value}`:''}`,{headers:headers()}),fetch('/v1/workers',{headers:headers()})]);
    if(!sum.ok)throw Error(sum.status);
    const summary=await sum.json(),jobs=await j.json(),workers=await w.json();
    live.textContent='Live';
    const cards=[['Online workers',`${summary.onlineWorkers}/${summary.workers}`],['Wachtend',(summary.jobsByStatus.QUEUED||0)+(summary.jobsByStatus.WAITING_FOR_WORKER||0)],['Actief',summary.jobsByStatus.RUNNING||0],['Mislukt',summary.jobsByStatus.FAILED||0]];
    document.querySelector('#summary').innerHTML=cards.map(([a,b])=>`<div class="stat"><small>${esc(a)}</small><strong>${b}</strong></div>`).join('');
    document.querySelector('#jobs').innerHTML=jobs.map(x=>`<tr><td>${esc(x.tenantId)}</td><td><strong>${esc(x.jobKey)}</strong><br><small>${esc(x.jobKind)}</small></td><td>${esc(x.provider)} · ${esc(x.model)}</td><td><span class="status">${esc(x.status)}</span><br><small>${esc(x.phase)}</small></td><td>${x.attemptCount}/${x.maxAttempts}</td><td>${new Date(x.updatedAt).toLocaleString('nl-NL')}</td></tr>`).join('')||'<tr><td colspan="6">Nog geen jobs.</td></tr>';
    document.querySelector('#workers').innerHTML=workers.map(x=>`<article class="worker"><strong>${esc(x.workerId)}</strong> <span class="status">${esc(x.status)}</span><p>${esc([...x.providers].join(', '))}<br>${esc([...x.capabilities].join(', '))}<br>Laatst gezien ${new Date(x.lastHeartbeatAt).toLocaleString('nl-NL')}</p></article>`).join('')||'<p>Nog geen worker geregistreerd.</p>';
  }catch(e){live.textContent='Geen toegang';}
}
status.onchange=load;setInterval(load,5000);configureLogin();load();
