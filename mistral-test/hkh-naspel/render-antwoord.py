#!/usr/bin/env python3
"""Rendert een naspel-resultaat (result.json) naar complete HTML met echte
beeldbank-foto's en klikbare bronlinks.

Gebruik: python3 render-antwoord.py <result.json> <output-antwoord.html> <pad-naar-bronnenmap> <pad-naar-fotomap>

Bronlogica: de bronbestanden bevatten records als
  [65] beeldbank 19637 "..." — foto
De 'data-hkh-source="N"'-ankers in answerHtml worden dan:
  - <a>-ankers  -> echte href naar de beeldbankpagina (of titel-attribuut bij niet-foto-bronnen)
  - <figure>    -> echte <img src="../../fotos/bron-N.jpg"> + figcaption + link
Alleen bronnen met een beeldbank-nummer krijgen een foto (zoals de originele
prompt voorschrijft). Niet-beeldbankbronnen (Heemskring, Dagblad) krijgen alleen een link.
"""
import json, re, sys, os, time, html
import urllib.request

BASE = "https://www.historischekringheemskerk.nl"
UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36"

def parse_bronnen(bronnenmap):
    """bronnummer -> dict(objectnr, titel, is_foto)"""
    bronnen = {}
    for mapnaam in sorted(os.listdir(bronnenmap)):
        pad = os.path.join(bronnenmap, mapnaam, "content")
        if not os.path.isfile(pad):
            continue
        tekst = open(pad, encoding="utf-8").read()
        for m in re.finditer(r"^\[(\d+)\] (.+?)(?=^\[|\Z)", tekst, re.MULTILINE | re.DOTALL):
            nummer = int(m.group(1))
            header = m.group(2).split("\n")[0]
            beeldbank = re.search(r"beeldbank\s+(\d+)", header)
            bronnen[nummer] = {
                "objectnr": int(beeldbank.group(1)) if beeldbank else None,
                "titel": header.strip(),
            }
    return bronnen

def download_foto(objectnr, doel):
    url = f"{BASE}/beeldbank/large/{objectnr}.jpg"
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        data = r.read()
    open(doel, "wb").write(data)
    return len(data)

def render(result_pad, antwoord_pad, bronnenmap, fotomap):
    d = json.load(open(result_pad, encoding="utf-8"))
    antwoord = d["answerHtml"]
    bronnen = parse_bronnen(bronnenmap)
    os.makedirs(fotomap, exist_ok=True)

    gebruikt = sorted({int(n) for n in re.findall(r'data-hkh-source="(\d+)"', antwoord)})
    foto_bronnen = [n for n in gebruikt if bronnen.get(n, {}).get("objectnr")]
    print(f"bronnen gebruikt: {len(gebruikt)}, waarvan beeldbank-foto: {len(foto_bronnen)}")

    gedownload, mislukt = 0, []
    for n in foto_bronnen:
        obj = bronnen[n]["objectnr"]
        doel = os.path.join(fotomap, f"bron-{n}.jpg")
        if os.path.exists(doel) and os.path.getsize(doel) > 0:
            continue
        try:
            download_foto(obj, doel)
            gedownload += 1
        except Exception as e:
            mislukt.append((n, obj, str(e)))
        time.sleep(0.4)  # netjes voor een verenigingswebsite
    if mislukt:
        print("mislukt:", mislukt)

    def fig_repl(m):
        n = int(m.group(1)); rest = m.group(2)
        b = bronnen.get(n, {})
        if b.get("objectnr") and os.path.exists(os.path.join(fotomap, f"bron-{n}.jpg")):
            img = f'<img src="{os.path.relpath(fotomap, os.path.dirname(antwoord_pad))}/bron-{n}.jpg" alt="{html.escape(b["titel"])}" loading="lazy">'
            link = f"{BASE}/cgi-bin/beeldbank.pl?ident={b['objectnr']}"
            return f'<figure data-hkh-source="{n}"><a href="{link}" target="_blank">{img}</a>{rest}</figure>'
        return m.group(0)

    def a_repl(m):
        n = int(m.group(1)); tekst = m.group(2)
        b = bronnen.get(n, {})
        if b.get("objectnr"):
            link = f"{BASE}/cgi-bin/beeldbank.pl?ident={b['objectnr']}"
            return f'<a data-hkh-source="{n}" href="{link}" target="_blank" title="{html.escape(b["titel"])}">{tekst}</a>'
        return f'<a data-hkh-source="{n}" title="{html.escape(b.get("titel","bron "+str(n)))}" style="text-decoration:underline dotted">{tekst}</a>'

    body = re.sub(r'<figure data-hkh-source="(\d+)">(.*?)</figure>', fig_repl, antwoord, flags=re.DOTALL)
    body = re.sub(r'<a data-hkh-source="(\d+)">(.*?)</a>', a_repl, body)

    kop = f"""<!doctype html><html lang="nl"><head><meta charset="utf-8">
<title>{html.escape(d.get('title', 'naspel-antwoord'))}</title>
<style>
 body{{max-width:820px;margin:2rem auto;padding:0 1rem;font-family:Georgia,serif;line-height:1.65;color:#222}}
 figure{{margin:1.6rem 0}} img{{max-width:100%;height:auto;border-radius:4px;box-shadow:0 1px 4px rgba(0,0,0,.25)}}
 figcaption{{font-size:.85em;color:#556;margin-top:.4rem;font-style:italic}}
 a{{color:#1a5276}} .meta{{background:#f4f6f7;border-left:4px solid #1a5276;padding:.6rem 1rem;font-size:.85em;margin-bottom:2rem}}
</style></head><body>
<div class="meta"><b>Naspel-antwoord</b> — {html.escape(d.get('title', '(geen titel in resultaat)'))} ·
{len(d.get('sources', []))} gebruikte bronnen · gegenereerd door Mistral Vibe naspel · foto's © Beeldbank Historische Kring Heemskerk</div>
{body}
</body></html>"""
    open(antwoord_pad, "w", encoding="utf-8").write(kop)
    print(f"geschreven: {antwoord_pad} ({len(kop)} tekens, {gedownload} foto's gedownload)")

if __name__ == "__main__":
    render(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4])
