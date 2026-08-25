# Agent Runtime — UX-concept

Dit is een klikbaar HTML-concept voor de eenvoudige toekomstige Flutter Web-monitor van Agent
Runtime. Het gebruikt uitsluitend synthetische voorbeelddata en doet geen API-calls. De monitor
heeft losse pagina's voor actieve jobs, de wachtrij, afgeronde jobs en workers. Vanuit een actieve
of afgeronde job opent een detail met resultaat en het beschikbare AI-conversatietranscript.

Open [`index.html`](index.html) rechtstreeks in een browser, of start in deze map:

```bash
python3 -m http.server 8080
```

Open daarna `http://localhost:8080`.

De functionele specificatie staat in [`../docs/beheerinterface.md`](../docs/beheerinterface.md) en is
leidend. Het prototype legt visuele richting, informatiehiërarchie, live transcriptupdates en
responsief gedrag vast; het is geen productiefrontend.
