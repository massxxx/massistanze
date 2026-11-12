/* MassiStanze — pagina per telefono, browser e PC */
(function () {
  'use strict';

  var REPO = 'https://github.com/massxxx/massistanze';
  var LINK_APK = REPO + '/releases/latest/download/MassiStanze.apk';
  var PORTA = 8766;
  var RATE = 48000, DUR = 0.1;
  var APP = !!window.STANZE_APP;
  var host = location.hostname;
  var SUL_PC = !APP && (host === 'localhost' || host === '127.0.0.1');
  var LS = 'massistanze-v1';

  var $ = function (s, r) { return (r || document).querySelector(s); };
  var el = function (tag, attrs, figli) {
    var e = document.createElement(tag);
    if (attrs) for (var k in attrs) {
      if (attrs[k] == null) continue;
      if (k === 'class') e.className = attrs[k];
      else if (k === 'html') e.innerHTML = attrs[k];
      else if (k === 'text') e.textContent = attrs[k];
      else if (k.slice(0, 2) === 'on') e.addEventListener(k.slice(2), attrs[k]);
      else if (k === 'style') e.style.cssText = attrs[k];
      else e.setAttribute(k, attrs[k]);
    }
    (figli || []).forEach(function (f) { if (f != null && f !== false) e.appendChild(typeof f === 'string' ? document.createTextNode(f) : f); });
    return e;
  };
  function mmss(s) {
    s = Math.max(0, Math.floor(s || 0));
    var h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60, x = s % 60;
    return (h ? h + ':' + (m < 10 ? '0' : '') : '') + m + ':' + (x < 10 ? '0' : '') + x;
  }

  // ------------------------------------------------------------ stato locale
  var st = {};
  try { st = JSON.parse(localStorage.getItem(LS) || '{}') || {}; } catch (e) { st = {}; }
  function salvaSt() { try { localStorage.setItem(LS, JSON.stringify(st)); } catch (e) {} }
  if (!st.id) { st.id = (SUL_PC ? 'pc-' : 'd-') + Math.random().toString(36).slice(2, 10); salvaSt(); }
  if (st.sempreAcceso == null) st.sempreAcceso = true;

  var base = APP ? (st.base || '') : location.origin;
  var S = null;                 // ultimo stato dal PC
  var LIB = { brani: [], playlist: [], radio: [] };
  var CHAT = [];
  var vista = 'onda';
  var ws = null, collegato = false, tentativi = 0;
  var foglioAperto = false;

  // ------------------------------------------------------------ richieste HTTP
  function richiesta(metodo, percorso, corpo, attesa) {
    var opz = { method: metodo, headers: { 'Content-Type': 'application/json' } };
    if (st.token) opz.headers['X-Stanze-Token'] = st.token;
    if (corpo !== undefined) opz.body = JSON.stringify(corpo);
    var ctrl = window.AbortController ? new AbortController() : null;
    if (ctrl) opz.signal = ctrl.signal;
    return new Promise(function (ok, ko) {
      var t = setTimeout(function () { if (ctrl) ctrl.abort(); ko(new Error('Il PC non risponde')); }, attesa || 8000);
      fetch(base + percorso, opz).then(function (r) {
        return r.json().catch(function () { return {}; }).then(function (j) {
          clearTimeout(t);
          if (r.status === 401) { chiediPin(); ko(new Error('PIN richiesto')); return; }
          if (!r.ok) { ko(new Error(j.errore || ('Errore ' + r.status))); return; }
          ok(j);
        });
      }).catch(function (e) { clearTimeout(t); ko(e && e.name === 'AbortError' ? new Error('Il PC non risponde') : new Error('PC non raggiungibile')); });
    });
  }
  function cmd(a, dati) {
    var m = dati || {}; m.t = 'cmd'; m.a = a;
    manda(m);
  }
  function manda(m) {
    if (ws && ws.readyState === 1) ws.send(JSON.stringify(m));
    else toast('Non collegato al PC');
  }

  // ============================================================ OROLOGIO COMUNE
  // differenza tra l'orologio del PC e quello di questo dispositivo (secondi)
  var campioni = [], scarto = null;
  function adesso() { return performance.now() / 1000; }
  function ping() { if (ws && ws.readyState === 1) ws.send(JSON.stringify({ t: 'ping', c: adesso() })); }
  function pong(m) {
    var t1 = adesso(), rtt = t1 - m.c;
    if (rtt < 0 || rtt > 2) return;
    campioni.push({ rtt: rtt, off: m.s - (m.c + t1) / 2, t: t1 });
    campioni = campioni.filter(function (c) { return t1 - c.t < 60; }).slice(-20);
    var migl = campioni.slice().sort(function (a, b) { return a.rtt - b.rtt; })[0];
    if (scarto == null || Math.abs(migl.off - scarto) > 0.05) scarto = migl.off;
    else scarto += (migl.off - scarto) * 0.3;
    sincro.rtt = migl.rtt;
  }
  function oraPC() { return scarto == null ? null : adesso() + scarto; }

  // ============================================================ LETTORE AUDIO
  var ctx = null, guadagno = null;
  var programmati = [];         // {T, src, fine}
  var fineCtx = null, ultimoT = null, velocita = 1;
  var mappa = null;             // contextTime - performanceTime (s), lisciata
  var sincro = { rtt: 0, err: 0, tardi: 0, ricevuti: 0 };

  function creaAudio() {
    if (ctx) return ctx;
    var AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return null;
    try { ctx = new AC({ latencyHint: 'playback', sampleRate: RATE }); } catch (e) { ctx = new AC(); }
    guadagno = ctx.createGain();
    guadagno.connect(ctx.destination);
    try { if (navigator.audioSession) navigator.audioSession.type = 'playback'; } catch (e) {}
    ctx.onstatechange = aggiornaSblocca;
    aggiornaVolume();
    return ctx;
  }
  function sbloccaAudio() {
    creaAudio();
    if (!ctx) { toast('Questo browser non può suonare l\'audio'); return; }
    // un istante di silenzio "sblocca" l'audio su iPhone
    try { var b = ctx.createBuffer(1, 1, 22050), s = ctx.createBufferSource(); s.buffer = b; s.connect(ctx.destination); s.start(0); } catch (e) {}
    ctx.resume().then(aggiornaSblocca, aggiornaSblocca);
  }
  function audioPronto() { return ctx && ctx.state === 'running'; }

  // ora del dispositivo audio in cui deve uscire il suono dell'istante P (performance, s)
  function ctxDaPerf(P) {
    var d;
    if (ctx.getOutputTimestamp) {
      var ts = ctx.getOutputTimestamp();
      if (ts && ts.performanceTime > 0) d = ts.contextTime - ts.performanceTime / 1000;
    }
    if (d == null) d = ctx.currentTime - adesso() - (ctx.outputLatency || ctx.baseLatency || 0);
    if (mappa == null || Math.abs(d - mappa) > 0.05) mappa = d;
    else mappa += (d - mappa) * 0.05;
    return P + mappa;
  }
  function mia() { return (S && S.stanze || []).filter(function (s) { return s.id === st.id; })[0] || {}; }
  function ritardo() { return (mia().ritardo || 0) / 1000; }

  function pezzo(buf) {
    sincro.ricevuti++;
    if (!audioPronto() || scarto == null) return;
    var dv = new DataView(buf);
    if (dv.getUint8(0) !== 1) return;
    var T = dv.getFloat64(8, true);
    var pcm = new Int16Array(buf, 16);
    var n = pcm.length >> 1;
    var dur = n / RATE;
    var quando = ctxDaPerf(T - scarto + ritardo());
    var inizio = quando, salta = 0, vel = 1;
    if (fineCtx != null && ultimoT != null && Math.abs(T - (ultimoT + DUR)) < 0.002) {
      var err = fineCtx - quando;                 // > 0: siamo in ritardo
      sincro.err = sincro.err * 0.9 + err * 0.1;
      if (Math.abs(err) < 0.04) {
        inizio = fineCtx;
        vel = 1 + Math.max(-0.004, Math.min(0.004, err * 0.5));
      }
    }
    var ora = ctx.currentTime + 0.01;
    if (inizio < ora) {
      salta = ora - inizio;
      if (salta >= dur - 0.002) { sincro.tardi++; fineCtx = null; ultimoT = null; return; }
      inizio = ora;
    }
    var ab = ctx.createBuffer(2, n, RATE);
    var L = ab.getChannelData(0), Rr = ab.getChannelData(1);
    for (var i = 0, j = 0; i < n; i++, j += 2) { L[i] = pcm[j] / 32768; Rr[i] = pcm[j + 1] / 32768; }
    var src = ctx.createBufferSource();
    src.buffer = ab;
    src.playbackRate.value = vel;
    src.connect(guadagno);
    src.start(inizio, salta);
    var voce = { T: T, src: src, fine: inizio + (dur - salta) / vel };
    programmati.push(voce);
    src.onended = function () { var k = programmati.indexOf(voce); if (k >= 0) programmati.splice(k, 1); };
    fineCtx = voce.fine;
    ultimoT = T;
    if (window.__debug) window.__debug.push([T, performance.timeOrigin + (inizio - salta * 0 - mappa) * 1000, vel]);
  }

  function taglia(da) {
    if (!ctx) return;
    var quando = scarto == null ? 0 : ctxDaPerf(da - scarto + ritardo());
    programmati.slice().forEach(function (p) {
      try {
        if (p.T >= da - 0.001) { p.src.stop(); p.src.disconnect(); programmati.splice(programmati.indexOf(p), 1); }
        else if (p.fine > quando) p.src.stop(Math.max(quando, ctx.currentTime));
      } catch (e) {}
    });
    fineCtx = null; ultimoT = null;
  }
  function silenzioTotale() {
    programmati.forEach(function (p) { try { p.src.stop(); p.src.disconnect(); } catch (e) {} });
    programmati = []; fineCtx = null; ultimoT = null;
  }

  function aggiornaVolume() {
    if (!guadagno || !S) return;
    var m = mia();
    var v = Math.pow((S.master == null ? 80 : S.master) / 100, 2) * Math.pow((m.volume == null ? 80 : m.volume) / 100, 2);
    guadagno.gain.setTargetAtTime(v, ctx.currentTime, 0.05);
  }

  function aggiornaSblocca() {
    var m = mia();
    var serve = collegato && (m.attivo !== false || (S && S.annuncio)) && !audioPronto();
    $('#sblocca').hidden = !serve;
  }

  // ============================================================ MICROFONO (annunci)
  var mic = null;
  function micPossibile() { return !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia); }
  function annuncio() {
    if (mic) return fermaAnnuncio();
    if (!micPossibile()) {
      apriFoglio('🎤 Annuncio', [el('p', { class: 'nota', style: 'font-size:14px', html:
        'Da qui il browser non permette di usare il microfono (serve una connessione sicura).<br><br>' +
        'Gli annunci si fanno dall\'<b>app Android</b> o dalla <b>pagina sul PC</b>.' + (APP ? '' : ' <a style="color:#ffb340" href="' + LINK_APK + '">Scarica MassiStanze.apk</a>') })]);
      return;
    }
    if (S && S.annuncio && S.annuncio !== st.id) { toast('Sta già parlando un\'altra stanza'); return; }
    creaAudio();
    ctx.resume();
    navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true } }).then(function (stream) {
      var sorg = ctx.createMediaStreamSource(stream);
      var proc = ctx.createScriptProcessor(2048, 1, 1);
      var muto = ctx.createGain(); muto.gain.value = 0;
      sorg.connect(proc); proc.connect(muto); muto.connect(ctx.destination);
      var freq = ctx.sampleRate;
      proc.onaudioprocess = function (e) {
        if (!ws || ws.readyState !== 1) return;
        var x = e.inputBuffer.getChannelData(0);
        var buf = new ArrayBuffer(16 + x.length * 2);
        var dv = new DataView(buf);
        dv.setUint8(0, 2); dv.setUint32(4, freq, true);
        var out = new Int16Array(buf, 16);
        for (var i = 0; i < x.length; i++) { var v = x[i] * 32767 * 1.4; out[i] = v > 32767 ? 32767 : v < -32768 ? -32768 : v; }
        ws.send(buf);
      };
      mic = { stream: stream, sorg: sorg, proc: proc, muto: muto };
      ws.send(JSON.stringify({ t: 'annuncio', on: true }));
      vibra(40);
      disegnaOnda();
    }).catch(function (e) {
      toast('Microfono non disponibile: ' + (e && e.name === 'NotAllowedError' ? 'permesso negato' : (e.message || e)));
    });
  }
  function fermaAnnuncio() {
    if (!mic) return;
    try { mic.proc.disconnect(); mic.sorg.disconnect(); mic.muto.disconnect(); } catch (e) {}
    mic.stream.getTracks().forEach(function (t) { t.stop(); });
    mic = null;
    if (ws && ws.readyState === 1) ws.send(JSON.stringify({ t: 'annuncio', on: false }));
    disegnaOnda();
  }

  // ============================================================ COLLEGAMENTO WEBSOCKET
  var timerPing = null;
  function apriWS() {
    if (ws) { try { ws.onclose = null; ws.close(); } catch (e) {} }
    if (!base) return chiediPin(true);
    var url = base.replace(/^http/, 'ws') + '/ws?id=' + encodeURIComponent(st.id) +
      '&nome=' + encodeURIComponent(st.nome || '') + (st.token ? '&token=' + encodeURIComponent(st.token) : '');
    var w = new WebSocket(url);
    w.binaryType = 'arraybuffer';
    ws = w;
    var aperto = false;
    w.onopen = function () {
      aperto = true; tentativi = 0; impostaCollegato(true);
      campioni = []; scarto = null;
      for (var i = 0; i < 8; i++) setTimeout(ping, i * 120);
      clearInterval(timerPing); timerPing = setInterval(ping, 2000);
    };
    w.onmessage = function (e) {
      if (typeof e.data !== 'string') return pezzo(e.data);
      var m; try { m = JSON.parse(e.data); } catch (x) { return; }
      ricevi(m);
    };
    w.onclose = function () {
      if (ws !== w) return;
      clearInterval(timerPing);
      impostaCollegato(false);
      silenzioTotale();
      if (mic) fermaAnnuncio();
      if (!aperto && !SUL_PC) {
        // forse il PIN non vale più: lo controlla con una richiesta normale
        richiesta('GET', '/api/ping', undefined, 4000).then(function (p) {
          if (!p.autorizzato) chiediPin(); else riprova();
        }).catch(riprova);
      } else riprova();
    };
  }
  function riprova() {
    tentativi++;
    setTimeout(function () { if (!collegato && $('#collega').hidden) apriWS(); }, Math.min(5000, 600 * tentativi));
  }
  function impostaCollegato(v) {
    collegato = v;
    $('#stato').className = 'stato ' + (v ? 'ok' : 'ko');
    $('#stato').title = v ? 'Collegato al PC' : 'Non collegato';
    var a = $('#avviso');
    if (!v && (st.token || SUL_PC)) { a.hidden = false; a.textContent = 'Collegamento al PC perso… riprovo'; }
    else a.hidden = true;
    aggiornaSblocca();
  }

  var nuoviChat = 0;
  function ricevi(m) {
    if (m.t === 'pong') return pong(m);
    if (m.t === 'taglia') return taglia(m.da);
    if (m.t === 'ciao') { $('#nome-stanza').dataset.pc = m.pc; return; }
    if (m.t === 'stato') {
      var prima = S;
      S = m;
      var io = mia();
      if (io.nome && io.nome !== st.nome) { st.nome = io.nome; salvaSt(); }
      $('#nome-stanza').textContent = (io.nome || '…') + (io.attivo === false ? ' · non suona' : ' · suona qui') + ($('#nome-stanza').dataset.pc ? ' — PC ' + $('#nome-stanza').dataset.pc : '');
      if (S.annuncio !== st.id && mic && prima && prima.annuncio === st.id) fermaAnnuncio();
      aggiornaVolume(); aggiornaSblocca(); mediaSession();
      ridisegna();
      return;
    }
    if (m.t === 'libreria') { LIB = m; ridisegna(); return; }
    if (m.t === 'chat_tutta') { CHAT = m.lista || []; if (vista === 'chat') disegnaChat(); return; }
    if (m.t === 'chat') {
      CHAT.push(m.msg); if (CHAT.length > 300) CHAT.shift();
      if (vista === 'chat') disegnaChat(true);
      else if (m.msg.cid !== st.id) {
        nuoviChat++; aggiornaBadge();
        toast('💬 ' + m.msg.da + ': ' + m.msg.testo.slice(0, 80));
        vibra([30, 60, 30]);
      }
      return;
    }
    if (m.t === 'avviso') { toast(m.testo); return; }
  }
  function aggiornaBadge() { var b = $('#badge'); b.hidden = !nuoviChat; b.textContent = nuoviChat > 99 ? '99+' : nuoviChat; }
  function vibra(p) { try { if (navigator.vibrate) navigator.vibrate(p); } catch (e) {} }

  // ============================================================ DISEGNO
  function ridisegna() {
    if (vista === 'onda') disegnaOnda();
    else if (vista === 'musica') disegnaMusica();
    else if (vista === 'radio') disegnaRadio();
    else if (vista === 'stanze') disegnaStanze();
    disegnaMini();
  }
  function vai(v) {
    vista = v; st.vista = v; salvaSt();
    Array.prototype.forEach.call(document.querySelectorAll('#schede button'), function (b) { b.classList.toggle('attiva', b.dataset.v === v); });
    Array.prototype.forEach.call(document.querySelectorAll('.vista'), function (s) { s.hidden = s.id !== 'v-' + v; });
    if (v === 'chat') { nuoviChat = 0; aggiornaBadge(); disegnaChat(); }
    if (v === 'radio' && !radioCaricate) caricaRadio('');
    ridisegna();
  }
  function brano(id) { for (var i = 0; i < LIB.brani.length; i++) if (LIB.brani[i].id === id) return LIB.brani[i]; return null; }

  function infoFonte() {
    var f = S && S.fonte;
    if (!f) return { tit: 'Niente in riproduzione', art: 'Carica musica o scegli una radio', ico: '🎵' };
    if (f.tipo === 'radio') return { tit: f.nome, art: f.info || 'Radio', ico: '📻', img: f.icona, onda: f.carica ? 'Collegamento alla radio…' : (f.in_onda ? '♪ ' + f.in_onda : '● In diretta') };
    if (f.tipo === 'prova') return { tit: f.titolo, art: f.artista, ico: '🔔' };
    return { tit: f.titolo || '?', art: f.artista || '', ico: '🎵', durata: f.durata };
  }
  function posAttuale() {
    if (!S) return 0;
    var p = S.pos;
    var o = oraPC();
    if (S.suona && o != null) p += Math.max(-1, o - S.posT);
    return Math.max(0, p);
  }
  function icona(info, cls) {
    var box = el('div', { class: cls || 'ico' }, [info.ico]);
    if (info.img) {
      var im = el('img', { src: info.img, alt: '', referrerpolicy: 'no-referrer' });
      im.onload = function () { box.textContent = ''; box.appendChild(im); };
    }
    return box;
  }

  var trascina = false;
  function disegnaOnda() {
    var v = $('#v-onda');
    if (vista !== 'onda') return;
    var sc = v.scrollTop;
    v.innerHTML = '';
    var info = infoFonte();
    var f = S && S.fonte;
    var cop = el('div', { class: 'copertina' }, [icona(info, 'grande-ico'),
      el('div', { class: 'cresce' }, [el('div', { class: 'tit', text: info.tit }), el('div', { class: 'art', text: info.art }),
        info.onda ? el('div', { class: 'onda', text: info.onda }) : null])]);
    v.appendChild(cop);
    if (S && S.avviso) v.appendChild(el('div', { class: 'fw attenzione', style: 'margin-top:10px', text: S.avviso }));

    var radio = f && f.tipo === 'radio';
    if (f && f.tipo === 'brano') {
      var r = el('input', { type: 'range', min: 0, max: Math.max(1, info.durata || 1), step: 1, value: posAttuale() });
      var t1 = el('span', { text: mmss(posAttuale()) }), t2 = el('span', { text: mmss(info.durata) });
      r.addEventListener('input', function () { trascina = true; t1.textContent = mmss(r.value); });
      r.addEventListener('change', function () { trascina = false; cmd('cerca', { pos: +r.value }); });
      v.appendChild(el('div', { class: 'barra-pos' }, [r, el('div', { class: 'tempi' }, [t1, t2])]));
      v._pos = { r: r, t1: t1 };
    } else v._pos = null;

    var suona = S && S.suona;
    var ripeti = S ? S.ripeti : 'no';
    v.appendChild(el('div', { class: 'comandi' }, [
      radio ? null : el('button', { class: 'tondo piccolo-t' + (S && S.casuale ? ' on' : ''), 'aria-label': 'Casuale', text: '🔀', onclick: function () { cmd('casuale'); } }),
      radio ? null : el('button', { class: 'tondo', 'aria-label': 'Precedente', text: '⏮', onclick: function () { cmd('prec'); } }),
      el('button', { class: 'tondo grande', 'aria-label': suona ? 'Pausa' : 'Play', text: suona ? '⏸' : '▶', onclick: function () {
        if (!audioPronto() && mia().attivo !== false) sbloccaAudio();
        cmd(suona ? (radio || (f && f.tipo === 'prova') ? 'stop' : 'pausa') : 'play');
      } }),
      radio ? null : el('button', { class: 'tondo', 'aria-label': 'Successivo', text: '⏭', onclick: function () { cmd('succ'); } }),
      radio ? null : el('button', { class: 'tondo piccolo-t' + (ripeti !== 'no' ? ' on' : ''), 'aria-label': 'Ripeti', onclick: function () { cmd('ripeti'); } },
        ['🔁', ripeti === 'uno' ? el('span', { class: 'uno', text: '1' }) : null])
    ]));

    // volume generale
    var mv = el('input', { type: 'range', min: 0, max: 100, value: S ? S.master : 80 });
    var mvv = el('span', { class: 'val', text: (S ? S.master : 80) + '%' });
    mv.addEventListener('input', function () { mvv.textContent = mv.value + '%'; if (S) { S.master = +mv.value; aggiornaVolume(); } });
    mv.addEventListener('change', function () { cmd('master', { v: +mv.value }); });
    v.appendChild(el('div', { class: 'volume' }, [el('span', { text: '🔊' }), mv, mvv]));
    v.appendChild(el('div', { class: 'piccolo', style: 'text-align:center;margin-top:-4px', text: 'Volume di tutte le stanze' }));

    // annuncio
    var altro = S && S.annuncio && S.annuncio !== st.id;
    var parla = (S && S.stanze || []).filter(function (s) { return s.id === S.annuncio; })[0];
    v.appendChild(el('button', { class: 'microfono' + (mic ? ' attivo' : ''), disabled: altro ? 'disabled' : null, onclick: annuncio,
      text: mic ? '🔴 Stai parlando a tutte le stanze — tocca per finire' : altro ? '🎤 Sta parlando: ' + (parla ? parla.nome : '…') : '🎤 Annuncio a tutte le stanze' }));

    // stanze veloci
    var stanze = (S && S.stanze || []).filter(function (s) { return s.online; });
    if (stanze.length) {
      v.appendChild(el('h3', { text: 'Dove suona' }));
      v.appendChild(el('div', { class: 'chips' }, stanze.map(function (s) {
        return el('button', { class: 'chip' + (s.attivo ? ' on' : ''), text: (s.attivo ? '🔊 ' : '🔇 ') + s.nome + (s.id === st.id ? ' (qui)' : ''),
          onclick: function () { if (s.id === st.id && !s.attivo) sbloccaAudio(); cmd('stanza', { id: s.id, attivo: !s.attivo }); } });
      })));
    }

    // coda
    var coda = S ? S.coda : [];
    if (coda.length) {
      v.appendChild(el('div', { class: 'riga', style: 'margin-top:14px' }, [el('h3', { class: 'cresce', style: 'margin:0', text: 'In coda (' + coda.length + ')' }),
        el('button', { class: 'chip', text: '🗑 Svuota', onclick: function () { conferma('Svuotare la coda?', 'Svuota', function () { cmd('svuota'); }); } })]));
      var lista = el('div', { class: 'elenco' });
      coda.forEach(function (id, i) {
        var b = brano(id) || { titolo: '?', artista: '' };
        var corr = f && f.tipo === 'brano' && i === S.indice;
        lista.appendChild(el('div', { class: 'voce' + (corr ? ' corrente' : '') }, [
          el('div', { class: 'ico', text: corr ? (S.suona ? '🔊' : '⏸') : String(i + 1) }),
          el('div', { class: 'testi', onclick: function () { if (!audioPronto() && mia().attivo !== false) sbloccaAudio(); cmd('vai', { i: i }); } },
            [el('div', { class: 'tt', text: b.titolo }), el('div', { class: 'ta', text: [b.artista, mmss(b.durata)].filter(Boolean).join(' · ') })]),
          i > 0 ? el('button', { class: 'mini-btn', 'aria-label': 'Su', text: '↑', onclick: function () { cmd('sposta', { i: i, j: i - 1 }); } }) : null,
          el('button', { class: 'mini-btn', 'aria-label': 'Togli', text: '✕', onclick: function () { cmd('togli', { i: i }); } })
        ]));
      });
      v.appendChild(lista);
    } else if (!radio) {
      v.appendChild(el('div', { class: 'vuoto', html: 'La coda è vuota.<br>Vai in <b>🎵 Musica</b> per caricare e scegliere i brani, o in <b>📻 Radio</b>.' }));
    }
    v.scrollTop = sc;
  }
  setInterval(function () {
    var v = $('#v-onda');
    if (vista === 'onda' && v._pos && !trascina) {
      var p = posAttuale();
      v._pos.r.value = p; v._pos.t1.textContent = mmss(p);
    }
  }, 500);

  function disegnaMini() {
    var m = $('#mini');
    if (vista === 'onda' || !S || !S.fonte) { m.hidden = true; return; }
    var info = infoFonte();
    m.hidden = false; m.innerHTML = '';
    m.appendChild(icona(info));
    m.appendChild(el('div', { class: 'testi' }, [el('div', { class: 'tt', text: info.tit }), el('div', { class: 'ta', text: info.onda || info.art })]));
    var radio = S.fonte.tipo !== 'brano';
    m.appendChild(el('button', { class: 'mini-btn', style: 'font-size:22px;color:var(--testo)', text: S.suona ? '⏸' : '▶', onclick: function (e) {
      e.stopPropagation(); if (!audioPronto() && mia().attivo !== false) sbloccaAudio(); cmd(S.suona ? (radio ? 'stop' : 'pausa') : 'play');
    } }));
    if (!radio) m.appendChild(el('button', { class: 'mini-btn', style: 'font-size:20px;color:var(--testo)', text: '⏭', onclick: function (e) { e.stopPropagation(); cmd('succ'); } }));
  }

  // ------------------------------------------------------------ MUSICA
  var filtro = '', sezioneMusica = st.sezioneMusica || 'brani';
  var caricamenti = [];          // {nome, perc, errore, fatto}
  function disegnaMusica() {
    var v = $('#v-musica');
    if (vista !== 'musica') return;
    var sc = v.scrollTop;
    var focus = document.activeElement && document.activeElement.id === 'filtro-musica';
    v.innerHTML = '';
    var file = el('input', { type: 'file', multiple: 'multiple', accept: 'audio/*,.mp3,.m4a,.aac,.wav,.flac,.ogg,.opus,.wma', style: 'display:none' });
    file.addEventListener('change', function () { carica(Array.prototype.slice.call(file.files)); file.value = ''; });
    v.appendChild(file);
    v.appendChild(el('button', { class: 'pri', style: 'width:100%', text: SUL_PC ? '⬆️ Aggiungi musica da questo PC' : '⬆️ Carica musica da questo telefono', onclick: function () { file.click(); } }));
    caricamenti.forEach(function (c) {
      v.appendChild(el('div', { class: 'caricamento' }, [el('div', { class: 'riga' }, [el('span', { class: 'cresce', text: c.nome }),
        el('span', { text: c.errore ? '✖' : c.fatto ? '✓' : c.perc + '%' })]),
        c.errore ? el('div', { style: 'color:var(--ko)', text: c.errore }) : c.fatto ? null : el('progress', { max: 100, value: c.perc })]));
    });
    var seg = el('div', { class: 'segmenti' }, [['brani', '🎵 Brani (' + LIB.brani.length + ')'], ['playlist', '📃 Playlist (' + LIB.playlist.length + ')']].map(function (x) {
      return el('button', { class: sezioneMusica === x[0] ? 'sel' : '', text: x[1], onclick: function () { sezioneMusica = x[0]; st.sezioneMusica = x[0]; salvaSt(); disegnaMusica(); } });
    }));
    v.appendChild(seg);
    if (sezioneMusica === 'brani') {
      if (!LIB.brani.length) {
        v.appendChild(el('div', { class: 'vuoto', html: 'Nessun brano ancora.<br>Premi <b>Carica musica</b> e scegli gli MP3 dal telefono: vengono salvati sul PC e li sentono tutte le stanze.' }));
      } else {
        var cerca = el('input', { type: 'search', id: 'filtro-musica', placeholder: '🔎 Cerca titolo o artista', value: filtro });
        cerca.addEventListener('input', function () { filtro = cerca.value; disegnaMusica(); });
        v.appendChild(cerca);
        var visti = elencoFiltrato();
        v.appendChild(el('div', { class: 'bottoni' }, [
          el('button', { class: 'sec', text: '▶ Suona tutti', onclick: function () { suona(visti.map(function (b) { return b.id; }), 0, false); } }),
          el('button', { class: 'sec', text: '🔀 Casuale', onclick: function () { suona(visti.map(function (b) { return b.id; }), 0, true); } })
        ]));
        var lista = el('div', { class: 'elenco' });
        var corr = S && S.fonte && S.fonte.tipo === 'brano' ? S.fonte.id : null;
        visti.forEach(function (b, i) {
          lista.appendChild(el('div', { class: 'voce' + (b.id === corr ? ' corrente' : '') }, [
            el('div', { class: 'ico', text: b.id === corr ? '🔊' : '🎵' }),
            el('div', { class: 'testi', onclick: function () { suona(visti.map(function (x) { return x.id; }), i, false); } },
              [el('div', { class: 'tt', text: b.titolo }), el('div', { class: 'ta', text: b.artista || b.nome_file || '' })]),
            el('span', { class: 'dx', text: mmss(b.durata) }),
            el('button', { class: 'mini-btn', 'aria-label': 'Altro', text: '⋮', onclick: function () { menuBrano(b, visti, i); } })
          ]));
        });
        if (!visti.length) lista.appendChild(el('div', { class: 'vuoto', text: 'Nessun brano trovato.' }));
        v.appendChild(lista);
      }
    } else {
      v.appendChild(el('button', { class: 'sec', style: 'width:100%', text: '➕ Nuova playlist', onclick: function () {
        chiediTesto('Nuova playlist', 'Nome', '', function (n) { cmd('playlist', { op: 'crea', nome: n }); });
      } }));
      var lp = el('div', { class: 'elenco', style: 'margin-top:8px' });
      LIB.playlist.forEach(function (p) {
        lp.appendChild(el('div', { class: 'voce' }, [el('div', { class: 'ico', text: '📃' }),
          el('div', { class: 'testi', onclick: function () { apriPlaylist(p.id); } }, [el('div', { class: 'tt', text: p.nome }), el('div', { class: 'ta', text: p.brani.length + ' brani · ' + mmss(durataTot(p.brani)) })]),
          el('button', { class: 'mini-btn', text: '▶', 'aria-label': 'Suona', onclick: function () { suona(p.brani, 0, false); } })]));
      });
      if (!LIB.playlist.length) lp.appendChild(el('div', { class: 'vuoto', text: 'Nessuna playlist. Creane una e aggiungi i brani.' }));
      v.appendChild(lp);
    }
    v.scrollTop = sc;
    if (focus) { var c = $('#filtro-musica'); if (c) { c.focus(); c.setSelectionRange(c.value.length, c.value.length); } }
  }
  function elencoFiltrato() {
    var q = filtro.trim().toLowerCase();
    return LIB.brani.slice().sort(function (a, b) { return (a.artista + a.titolo).toLowerCase() < (b.artista + b.titolo).toLowerCase() ? -1 : 1; })
      .filter(function (b) { return !q || (b.titolo + ' ' + b.artista + ' ' + (b.nome_file || '')).toLowerCase().indexOf(q) >= 0; });
  }
  function durataTot(ids) { return ids.reduce(function (s, id) { var b = brano(id); return s + (b ? b.durata || 0 : 0); }, 0); }
  function suona(ids, da, mescola) {
    if (!ids.length) return;
    if (!audioPronto() && mia().attivo !== false) sbloccaAudio();
    cmd('suona', { ids: ids, da: da, mescola: mescola });
    toast('▶ ' + ((brano(ids[mescola ? 0 : da]) || {}).titolo || ''));
  }
  function menuBrano(b, lista, i) {
    apriFoglio(b.titolo, [el('div', { class: 'menu' }, [
      el('button', { text: '▶ Suona ora', onclick: function () { chiudiFoglio(); suona(lista.map(function (x) { return x.id; }), i, false); } }),
      el('button', { text: '⏭ Suona dopo questo', onclick: function () { chiudiFoglio(); cmd('accoda', { ids: [b.id], dopo: true }); toast('Suonerà dopo il brano attuale'); } }),
      el('button', { text: '➕ Aggiungi in fondo alla coda', onclick: function () { chiudiFoglio(); cmd('accoda', { ids: [b.id] }); toast('Aggiunto alla coda'); } }),
      el('button', { text: '📃 Aggiungi a una playlist…', onclick: function () { scegliPlaylist([b.id]); } }),
      el('button', { text: '✏️ Rinomina', onclick: function () {
        var t = el('input', { value: b.titolo }), a = el('input', { value: b.artista || '' });
        apriFoglio('Rinomina', [el('label', { text: 'Titolo' }), t, el('label', { text: 'Artista' }), a,
          el('div', { class: 'bottoni', style: 'margin-top:14px' }, [el('button', { class: 'sec', text: 'Annulla', onclick: chiudiFoglio }),
            el('button', { class: 'pri', text: 'Salva', onclick: function () { cmd('rinomina', { id: b.id, titolo: t.value, artista: a.value }); chiudiFoglio(); } })])]);
      } }),
      el('button', { class: 'rosso', text: '🗑 Elimina dal PC', onclick: function () {
        conferma('Eliminare «' + b.titolo + '»? Il file viene cancellato dal PC.', 'Elimina', function () { cmd('elimina', { id: b.id }); });
      } })
    ])]);
  }
  function scegliPlaylist(ids) {
    var voci = LIB.playlist.map(function (p) {
      return el('button', { text: '📃 ' + p.nome, onclick: function () { cmd('playlist', { op: 'aggiungi', id: p.id, ids: ids }); chiudiFoglio(); toast('Aggiunto a ' + p.nome); } });
    });
    voci.push(el('button', { text: '➕ Nuova playlist…', onclick: function () {
      chiediTesto('Nuova playlist', 'Nome', '', function (n) { cmd('playlist', { op: 'crea', nome: n, ids: ids }); toast('Playlist creata'); });
    } }));
    apriFoglio('Aggiungi a playlist', [el('div', { class: 'menu' }, voci)]);
  }
  function apriPlaylist(pid) {
    var p = LIB.playlist.filter(function (x) { return x.id === pid; })[0];
    if (!p) return chiudiFoglio();
    var lista = el('div', { class: 'elenco' });
    p.brani.forEach(function (id, i) {
      var b = brano(id); if (!b) return;
      lista.appendChild(el('div', { class: 'voce' }, [el('div', { class: 'ico', text: String(i + 1) }),
        el('div', { class: 'testi', onclick: function () { chiudiFoglio(); suona(p.brani, i, false); } }, [el('div', { class: 'tt', text: b.titolo }), el('div', { class: 'ta', text: b.artista })]),
        i > 0 ? el('button', { class: 'mini-btn', text: '↑', onclick: function () { var x = p.brani.slice(); x.splice(i - 1, 0, x.splice(i, 1)[0]); cmd('playlist', { op: 'ordina', id: p.id, ids: x }); p.brani = x; apriPlaylist(pid); } }) : null,
        el('button', { class: 'mini-btn', text: '✕', onclick: function () { cmd('playlist', { op: 'togli', id: p.id, ids: [id] }); p.brani = p.brani.filter(function (x) { return x !== id; }); apriPlaylist(pid); } })]));
    });
    if (!p.brani.length) lista.appendChild(el('div', { class: 'vuoto', text: 'Playlist vuota: aggiungi dei brani.' }));
    apriFoglio('📃 ' + p.nome, [
      el('div', { class: 'bottoni' }, [el('button', { class: 'pri', text: '▶ Suona', onclick: function () { chiudiFoglio(); suona(p.brani, 0, false); } }),
        el('button', { class: 'sec', text: '🔀 Casuale', onclick: function () { chiudiFoglio(); suona(p.brani, 0, true); } })]),
      el('div', { class: 'bottoni' }, [el('button', { class: 'sec', text: '➕ Aggiungi brani', onclick: function () { sceltaBrani(p); } }),
        el('button', { class: 'sec', text: '⏭ In coda', onclick: function () { cmd('accoda', { ids: p.brani }); chiudiFoglio(); toast('Aggiunta alla coda'); } })]),
      lista,
      el('div', { class: 'bottoni', style: 'margin-top:12px' }, [
        el('button', { class: 'sec', text: '✏️ Rinomina', onclick: function () { chiediTesto('Rinomina playlist', 'Nome', p.nome, function (n) { cmd('playlist', { op: 'rinomina', id: p.id, nome: n }); }); } }),
        el('button', { class: 'peri', text: '🗑 Elimina', onclick: function () { conferma('Eliminare la playlist «' + p.nome + '»? I brani restano.', 'Elimina', function () { cmd('playlist', { op: 'elimina', id: p.id }); }); } })])
    ]);
  }
  function sceltaBrani(p) {
    var scelti = {};
    var box = el('div', { class: 'scegli elenco' });
    var q = el('input', { type: 'search', placeholder: '🔎 Cerca' });
    function mostra() {
      box.innerHTML = '';
      var t = q.value.trim().toLowerCase();
      LIB.brani.forEach(function (b) {
        if (p.brani.indexOf(b.id) >= 0) return;
        if (t && (b.titolo + ' ' + b.artista).toLowerCase().indexOf(t) < 0) return;
        var sp = el('div', { class: 'spunta' + (scelti[b.id] ? ' on' : ''), text: scelti[b.id] ? '✓' : '' });
        box.appendChild(el('div', { class: 'voce', onclick: function () { scelti[b.id] = !scelti[b.id]; mostra(); } },
          [sp, el('div', { class: 'testi' }, [el('div', { class: 'tt', text: b.titolo }), el('div', { class: 'ta', text: b.artista })])]));
      });
      if (!box.children.length) box.appendChild(el('div', { class: 'vuoto', text: 'Nessun altro brano da aggiungere.' }));
    }
    q.addEventListener('input', mostra);
    mostra();
    apriFoglio('Aggiungi a «' + p.nome + '»', [q, box, el('div', { class: 'bottoni', style: 'margin-top:12px' }, [
      el('button', { class: 'sec', text: 'Indietro', onclick: function () { apriPlaylist(p.id); } }),
      el('button', { class: 'pri', text: 'Aggiungi', onclick: function () {
        var ids = Object.keys(scelti).filter(function (k) { return scelti[k]; });
        if (ids.length) { cmd('playlist', { op: 'aggiungi', id: p.id, ids: ids }); p.brani = p.brani.concat(ids); }
        apriPlaylist(p.id);
      } })])]);
  }

  // caricamento dei file sul PC (uno alla volta, con avanzamento)
  var codaCarica = [], caricando = false;
  function carica(files) {
    files.forEach(function (f) { var c = { nome: f.name, perc: 0, file: f }; caricamenti.push(c); codaCarica.push(c); });
    disegnaMusica();
    prossimoCarica();
  }
  function prossimoCarica() {
    if (caricando) return;
    var c = codaCarica.shift();
    if (!c) {
      setTimeout(function () { caricamenti = caricamenti.filter(function (x) { return x.errore || !x.fatto; }); disegnaMusica(); }, 2500);
      return;
    }
    caricando = true;
    var x = new XMLHttpRequest();
    x.open('POST', base + '/api/carica');
    if (st.token) x.setRequestHeader('X-Stanze-Token', st.token);
    x.setRequestHeader('X-Nome', encodeURIComponent(c.nome));
    var ultimo = 0;
    x.upload.onprogress = function (e) {
      if (e.lengthComputable) { c.perc = Math.round(e.loaded / e.total * 100); if (Date.now() - ultimo > 300) { ultimo = Date.now(); disegnaMusica(); } }
    };
    x.onload = function () {
      var j = {}; try { j = JSON.parse(x.responseText); } catch (e) {}
      if (x.status === 200) c.fatto = true; else c.errore = j.errore || ('Errore ' + x.status);
      caricando = false; c.file = null; disegnaMusica(); prossimoCarica();
    };
    x.onerror = function () { c.errore = 'Il PC non risponde'; caricando = false; disegnaMusica(); prossimoCarica(); };
    x.send(c.file);
  }

  // ------------------------------------------------------------ RADIO
  var radioCaricate = false, radioLista = [], radioCerca = '', radioStato = '';
  function caricaRadio(q) {
    radioCaricate = true; radioCerca = q; radioStato = 'Cerco le radio…'; radioLista = [];
    disegnaRadio();
    richiesta('GET', '/api/radio?q=' + encodeURIComponent(q), undefined, 20000).then(function (r) {
      radioLista = r.radio || []; radioStato = radioLista.length ? '' : 'Nessuna radio trovata.';
      disegnaRadio();
    }).catch(function (e) { radioStato = 'Elenco non disponibile: ' + e.message + '. Il PC è collegato a Internet?'; radioCaricate = false; disegnaRadio(); });
  }
  function vocRadio(r) {
    var pref = LIB.radio.some(function (x) { return x.url === r.url; });
    var att = S && S.fonte && S.fonte.tipo === 'radio' && S.fonte.url === r.url;
    return el('div', { class: 'voce' + (att ? ' corrente' : '') }, [icona({ ico: '📻', img: r.icona }),
      el('div', { class: 'testi', onclick: function () { if (!audioPronto() && mia().attivo !== false) sbloccaAudio(); cmd('radio', { radio: r }); toast('📻 ' + r.nome); } },
        [el('div', { class: 'tt', text: r.nome }), el('div', { class: 'ta', text: r.info || r.url })]),
      el('button', { class: 'mini-btn' + (pref ? ' stella' : ''), text: pref ? '★' : '☆', 'aria-label': 'Preferita', onclick: function () { cmd('preferita', { radio: r, on: !pref }); } })]);
  }
  function disegnaRadio() {
    var v = $('#v-radio');
    if (vista !== 'radio') return;
    var sc = v.scrollTop;
    v.innerHTML = '';
    var q = el('input', { type: 'search', placeholder: '🔎 Cerca una radio (es. Deejay, RTL, jazz…)', value: radioCerca, enterkeyhint: 'search' });
    q.addEventListener('keydown', function (e) { if (e.key === 'Enter') { q.blur(); caricaRadio(q.value.trim()); } });
    v.appendChild(el('div', { class: 'riga' }, [q, el('button', { class: 'pri', style: 'flex:none', text: 'Cerca', onclick: function () { caricaRadio(q.value.trim()); } })]));
    if (LIB.radio.length) {
      v.appendChild(el('h3', { text: '⭐ Preferite' }));
      v.appendChild(el('div', { class: 'elenco' }, LIB.radio.map(vocRadio)));
    }
    v.appendChild(el('div', { class: 'riga', style: 'margin-top:16px' }, [el('h3', { class: 'cresce', style: 'margin:0', text: radioCerca ? 'Risultati per «' + radioCerca + '»' : 'Le più ascoltate in Italia' }),
      radioCerca ? el('button', { class: 'chip', text: '🇮🇹 Italia', onclick: function () { caricaRadio(''); } }) : null]));
    if (radioStato) v.appendChild(el('div', { class: 'vuoto', text: radioStato }));
    v.appendChild(el('div', { class: 'elenco' }, radioLista.map(vocRadio)));
    v.appendChild(el('button', { class: 'sec', style: 'width:100%;margin-top:14px', text: '➕ Aggiungi una radio con l\'indirizzo', onclick: function () {
      var n = el('input', { placeholder: 'Nome' }), u = el('input', { placeholder: 'https://…', inputmode: 'url' });
      apriFoglio('Radio con indirizzo', [el('label', { text: 'Nome' }), n, el('label', { text: 'Indirizzo dello streaming (mp3, aac, m3u8…)' }), u,
        el('div', { class: 'bottoni', style: 'margin-top:14px' }, [el('button', { class: 'sec', text: '▶ Prova', onclick: function () {
          if (!/^https?:\/\//.test(u.value.trim())) return toast('L\'indirizzo deve iniziare con http');
          cmd('radio', { radio: { nome: n.value.trim() || 'Radio', url: u.value.trim() } });
        } }), el('button', { class: 'pri', text: '⭐ Salva', onclick: function () {
          if (!/^https?:\/\//.test(u.value.trim())) return toast('L\'indirizzo deve iniziare con http');
          cmd('preferita', { on: true, radio: { nome: n.value.trim() || 'Radio', url: u.value.trim() } }); chiudiFoglio();
        } })])]);
    } }));
    v.appendChild(el('div', { class: 'nota', style: 'text-align:center', text: 'Elenco radio da radio-browser.info' }));
    v.scrollTop = sc;
  }

  // ------------------------------------------------------------ STANZE
  function disegnaStanze() {
    var v = $('#v-stanze');
    if (vista !== 'stanze') return;
    if (trascinaStanza) return;
    var sc = v.scrollTop;
    v.innerHTML = '';
    var stanze = S ? S.stanze.slice() : [];
    stanze.sort(function (a, b) { return (b.id === st.id) - (a.id === st.id); });
    v.appendChild(el('h2', { text: '🏠 Stanze' }));
    v.appendChild(el('div', { class: 'nota', text: 'Ogni dispositivo collegato è una stanza. Scegli dove suona, il volume e il ritardo (serve con casse Bluetooth, che arrivano in ritardo).' }));
    stanze.forEach(function (s) { v.appendChild(schedaStanza(s)); });
    v.appendChild(el('h3', { text: 'Sincronia' }));
    v.appendChild(el('div', { class: 'nota', html: 'Avvia la <b>prova</b>: tutte le stanze fanno un «bip» ogni secondo. Avvicinati a due stanze e sposta il <b>ritardo</b> di quella che arriva prima finché i bip coincidono.' }));
    var prova = S && S.fonte && S.fonte.tipo === 'prova' && S.suona;
    v.appendChild(el('button', { class: prova ? 'peri' : 'sec', style: 'width:100%', text: prova ? '⏹ Ferma la prova' : '🔔 Prova sincronia', onclick: function () {
      if (!audioPronto()) sbloccaAudio(); cmd(prova ? 'stop' : 'prova');
    } }));
    v.appendChild(el('div', { class: 'nota', id: 'info-sincro' }));
    infoSincro();
    v.scrollTop = sc;
  }
  function infoSincro() {
    var x = $('#info-sincro');
    if (!x) return;
    x.textContent = 'Questo dispositivo: rete ' + Math.round(sincro.rtt * 1000) + ' ms · scarto ' + Math.round(sincro.err * 1000) + ' ms · audio ' +
      (ctx ? (ctx.state === 'running' ? 'attivo' : 'fermo') + (ctx.outputLatency ? ', uscita ' + Math.round(ctx.outputLatency * 1000) + ' ms' : '') : 'non avviato') +
      (sincro.tardi ? ' · ' + sincro.tardi + ' pacchetti in ritardo' : '');
  }
  setInterval(function () { if (vista === 'stanze') infoSincro(); }, 1000);
  var trascinaStanza = false;
  function schedaStanza(s) {
    var qui = s.id === st.id;
    var att = el('input', { type: 'checkbox' }); att.checked = s.attivo !== false;
    att.addEventListener('change', function () { if (qui && att.checked) sbloccaAudio(); cmd('stanza', { id: s.id, attivo: att.checked }); });
    function cursore(campo, min, max, passo, fmt) {
      var r = el('input', { type: 'range', min: min, max: max, step: passo, value: s[campo] || 0 });
      var val = el('span', { class: 'val', style: 'width:64px', text: fmt(s[campo] || 0) });
      r.addEventListener('input', function () {
        trascinaStanza = true; val.textContent = fmt(+r.value);
        if (qui) { mia()[campo] = +r.value; if (campo === 'volume') aggiornaVolume(); }
      });
      r.addEventListener('change', function () { trascinaStanza = false; var d = { id: s.id }; d[campo] = +r.value; cmd('stanza', d); });
      return [r, val];
    }
    var vol = cursore('volume', 0, 100, 1, function (x) { return x + '%'; });
    var rit = cursore('ritardo', -300, 1500, 10, function (x) { return (x > 0 ? '+' : '') + x + ' ms'; });
    return el('div', { class: 'stanza' + (qui ? ' mia' : '') }, [
      el('div', { class: 'riga' }, [el('span', { class: 'pallino' + (s.online ? ' on' : '') }), el('span', { class: 'nome', text: s.nome + (qui ? ' (questo)' : '') }),
        s.mic ? el('span', { text: '🎤' }) : null,
        el('button', { class: 'mini-btn', text: '✏️', 'aria-label': 'Rinomina', onclick: function () { chiediTesto('Nome della stanza', 'es. Cucina, Camera, Salotto', s.nome, function (n) { cmd('stanza', { id: s.id, nome: n }); }); } }),
        !s.online ? el('button', { class: 'mini-btn', text: '🗑', 'aria-label': 'Dimentica', onclick: function () { conferma('Togliere «' + s.nome + '» dall\'elenco? Se si ricollega torna da sola.', 'Togli', function () { cmd('dimentica', { id: s.id }); }); } }) : null]),
      s.online ? null : el('div', { class: 'piccolo', text: 'Non collegata' }),
      el('label', { class: 'interr' }, [el('span', { text: 'Suona qui la musica' }), att]),
      el('div', { class: 'volume' }, [el('span', { text: '🔊' })].concat(vol)),
      el('div', { class: 'volume' }, [el('span', { text: '⏱' })].concat(rit))
    ]);
  }

  // ------------------------------------------------------------ CHAT
  function disegnaChat(nuovo) {
    var v = $('#v-chat');
    var lista = $('.messaggi', v);
    if (!lista) {
      lista = el('div', { class: 'messaggi' });
      var t = el('textarea', { placeholder: 'Scrivi a tutte le stanze…', rows: 1, enterkeyhint: 'send' });
      var invia = function () {
        var x = t.value.trim();
        if (!x) return;
        manda({ t: 'chat', testo: x });
        t.value = ''; t.focus();
      };
      t.addEventListener('keydown', function (e) { if (e.key === 'Enter' && !e.shiftKey && !('ontouchstart' in window)) { e.preventDefault(); invia(); } });
      v.appendChild(lista);
      v.appendChild(el('div', { class: 'scrivi' }, [t, el('button', { 'aria-label': 'Invia', text: '➤', onclick: invia })]));
    }
    var giu = nuovo ? lista.scrollHeight - lista.scrollTop - lista.clientHeight < 120 : true;
    lista.innerHTML = '';
    if (!CHAT.length) lista.appendChild(el('div', { class: 'vuoto', html: 'Nessun messaggio.<br>Quello che scrivi qui arriva a tutti i dispositivi collegati: app, browser e PC.' }));
    var giorno = '';
    CHAT.forEach(function (m) {
      var d = new Date(m.ora * 1000);
      var g = d.toLocaleDateString('it-IT', { weekday: 'long', day: 'numeric', month: 'long' });
      if (g !== giorno) { giorno = g; lista.appendChild(el('div', { class: 'giorno', text: g })); }
      var mio = m.cid === st.id;
      lista.appendChild(el('div', { class: 'bolla' + (mio ? ' mia' : '') }, [mio ? null : el('div', { class: 'chi', text: m.da }), m.testo,
        el('div', { class: 'quando', text: d.toLocaleTimeString('it-IT', { hour: '2-digit', minute: '2-digit' }) })]));
    });
    if (giu) lista.scrollTop = lista.scrollHeight;
  }

  // ============================================================ FINESTRE
  function toast(msg) {
    var t = $('#toast'); t.textContent = msg; t.hidden = false;
    clearTimeout(toast.t); toast.t = setTimeout(function () { t.hidden = true; }, 3000);
  }
  function apriFoglio(titolo, contenuto) {
    var f = $('#foglio'); f.innerHTML = '';
    f.appendChild(el('div', { class: 'testa' }, [el('h2', { text: titolo }), el('button', { class: 'icobtn', text: '✕', 'aria-label': 'Chiudi', onclick: chiudiFoglio })]));
    contenuto.forEach(function (c) { if (c) f.appendChild(c); });
    f.hidden = false; $('#velo').hidden = false; foglioAperto = true;
  }
  function chiudiFoglio() { $('#foglio').hidden = true; $('#velo').hidden = true; foglioAperto = false; }
  function conferma(domanda, si, fatto) {
    apriFoglio('Sei sicuro?', [el('p', { text: domanda }), el('div', { class: 'bottoni' }, [
      el('button', { class: 'sec', text: 'Annulla', onclick: chiudiFoglio }),
      el('button', { class: 'peri', text: si, onclick: function () { chiudiFoglio(); fatto(); } })])]);
  }
  function chiediTesto(titolo, segnaposto, valore, fatto) {
    var i = el('input', { placeholder: segnaposto, value: valore || '' });
    var ok = function () { var x = i.value.trim(); if (!x) return; chiudiFoglio(); fatto(x); };
    i.addEventListener('keydown', function (e) { if (e.key === 'Enter') ok(); });
    apriFoglio(titolo, [i, el('div', { class: 'bottoni', style: 'margin-top:12px' }, [el('button', { class: 'sec', text: 'Annulla', onclick: chiudiFoglio }), el('button', { class: 'pri', text: 'OK', onclick: ok })])]);
    setTimeout(function () { i.focus(); i.select(); }, 100);
  }

  function impostazioni() {
    var acceso = el('input', { type: 'checkbox' }); acceso.checked = !!st.sempreAcceso;
    acceso.addEventListener('change', function () { st.sempreAcceso = acceso.checked; salvaSt(); schermoAcceso(); });
    var io = mia();
    apriFoglio('⚙️ Impostazioni', [
      el('div', { class: 'menu' }, [
        el('button', { text: '✏️ Nome di questa stanza: ' + (io.nome || '—'), onclick: function () {
          chiediTesto('Nome di questa stanza', 'es. Cucina, Camera, Salotto', io.nome, function (n) { st.nome = n; salvaSt(); cmd('stanza', { id: st.id, nome: n }); });
        } })
      ]),
      el('label', { class: 'interr' }, [el('span', { text: 'Schermo sempre acceso (consigliato: il telefono continua a suonare)' }), acceso]),
      el('div', { class: 'menu' }, [
        el('button', { text: '💬 Svuota la chat per tutti', onclick: function () { conferma('Cancellare tutti i messaggi della chat?', 'Cancella', function () { manda({ t: 'chat_svuota' }); }); } }),
        SUL_PC ? null : el('button', { class: 'rosso', text: APP ? '🔌 Collega a un altro PC' : '🔌 Scollega questo dispositivo', onclick: function () {
          st.token = ''; if (APP) st.base = ''; salvaSt(); chiudiFoglio(); if (ws) { ws.onclose = null; ws.close(); } impostaCollegato(false); chiediPin(true);
        } })
      ]),
      APP || SUL_PC ? null : el('div', { class: 'nota', html: 'Su Android c\'è anche l\'app (serve per gli annunci col microfono): <a style="color:#ffb340" href="' + LINK_APK + '">scarica MassiStanze.apk</a>.<br>Su iPhone: Condividi › «Aggiungi alla schermata Home».' }),
      el('div', { class: 'nota', text: 'MassiStanze — musica sincronizzata in tutte le stanze. Spotify e YouTube non si possono usare: il loro audio è protetto.' })
    ]);
  }

  // ------------------------------------------------------------ schermo acceso, controlli di sistema
  var blocco = null;
  function schermoAcceso() {
    if (!st.sempreAcceso) { if (blocco) { blocco.release(); blocco = null; } return; }
    if (navigator.wakeLock && document.visibilityState === 'visible') navigator.wakeLock.request('screen').then(function (b) { blocco = b; }).catch(function () {});
  }
  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'visible') { schermoAcceso(); if (ctx && ctx.state !== 'running') ctx.resume().catch(function () {}); if (!collegato && $('#collega').hidden) apriWS(); }
  });
  function mediaSession() {
    if (!('mediaSession' in navigator) || !S) return;
    try {
      var info = infoFonte();
      navigator.mediaSession.metadata = new MediaMetadata({ title: info.tit, artist: info.art, album: 'MassiStanze', artwork: [{ src: 'icona-512.png', sizes: '512x512', type: 'image/png' }] });
      navigator.mediaSession.playbackState = S.suona ? 'playing' : 'paused';
      if (!mediaSession.fatto) {
        mediaSession.fatto = true;
        navigator.mediaSession.setActionHandler('play', function () { cmd('play'); });
        navigator.mediaSession.setActionHandler('pause', function () { cmd('pausa'); });
        navigator.mediaSession.setActionHandler('nexttrack', function () { cmd('succ'); });
        navigator.mediaSession.setActionHandler('previoustrack', function () { cmd('prec'); });
      }
    } catch (e) {}
  }

  // ------------------------------------------------------------ PIN e ricerca del PC
  function chiediPin(forzaIndirizzo) {
    if (SUL_PC) return;
    var s = $('#collega');
    if (!s.hidden) return;
    s.hidden = false;
    $('#collega-indirizzo').hidden = !APP;
    if (APP && st.base) $('#in-ip').value = st.base.replace(/^https?:\/\//, '').replace(':' + PORTA, '');
    $('#collega-errore').textContent = '';
    if (!forzaIndirizzo && APP && st.base) setTimeout(function () { $('#in-pin').focus(); }, 200);
  }
  function normalizzaIndirizzo(v) {
    v = v.trim().replace(/\/+$/, '');
    if (!v) return '';
    if (!/^https?:\/\//.test(v)) v = 'http://' + v;
    if (!/:\d+$/.test(v.replace(/^https?:\/\//, ''))) v += ':' + PORTA;
    return v;
  }
  function collega(pin) {
    var err = $('#collega-errore');
    err.textContent = '';
    if (APP) {
      var b = normalizzaIndirizzo($('#in-ip').value);
      if (!b) { err.textContent = 'Scrivi l\'indirizzo del PC o usa «Cerca»'; return; }
      base = b;
    }
    if (!/^\d{4}$/.test(pin)) { err.textContent = 'Il PIN è di 4 cifre'; return; }
    var btn = $('#b-collega'); btn.disabled = true; btn.textContent = 'Collego…';
    st.token = '';
    sbloccaAudio();                               // il tocco su «Collega» sblocca anche l'audio
    richiesta('POST', '/api/accedi', { pin: pin }).then(function (r) {
      st.token = r.token; if (APP) st.base = base; salvaSt();
      $('#collega').hidden = true; $('#in-pin').value = '';
      toast('Collegato a ' + r.nome + ' ✓');
      apriWS();
      if (!st.nome) setTimeout(function () {
        chiediTesto('Come si chiama questa stanza?', 'es. Cucina, Camera, Salotto', '', function (n) { st.nome = n; salvaSt(); cmd('stanza', { id: st.id, nome: n }); });
      }, 800);
    }).catch(function (e) {
      err.textContent = e.message === 'PC non raggiungibile' || e.message === 'Il PC non risponde'
        ? 'Non trovo MassiStanze a quell\'indirizzo. È acceso? Il firewall di Windows lo lascia passare? Siete sullo stesso Wi-Fi?' : e.message;
    }).then(function () { btn.disabled = false; btn.textContent = 'Collega'; });
  }
  var cercando = false;
  function cercaPC() {
    if (cercando) return;
    cercando = true;
    var stato = $('#cerca-stato');
    var reti = ['192.168.1', '192.168.0', '192.168.178', '192.168.2', '192.168.100', '10.0.0', '192.168.10', '192.168.50'];
    var scritta = $('#in-ip').value.trim().match(/^(\d+\.\d+\.\d+)\./);
    if (scritta) reti.unshift(scritta[1]);
    var coda = [];
    reti.forEach(function (r) { for (var i = 1; i < 255; i++) coda.push(r + '.' + i); });
    var totale = coda.length, fatti = 0, trovato = null, attivi = 0;
    function prova(ip) {
      return new Promise(function (ok) {
        var ctrl = window.AbortController ? new AbortController() : null;
        var t = setTimeout(function () { if (ctrl) ctrl.abort(); ok(null); }, 1500);
        fetch('http://' + ip + ':' + PORTA + '/api/ping', ctrl ? { signal: ctrl.signal } : {}).then(function (r) { return r.json(); })
          .then(function (j) { clearTimeout(t); ok(j && j.app === 'MassiStanze' ? { ip: ip, nome: j.nome } : null); })
          .catch(function () { clearTimeout(t); ok(null); });
      });
    }
    function prossimo() {
      if (trovato || !coda.length) { if (attivi === 0) fine(); return; }
      var ip = coda.shift(); attivi++;
      prova(ip).then(function (r) {
        attivi--; fatti++;
        if (r && !trovato) trovato = r;
        if (fatti % 20 === 0) stato.textContent = 'Cerco… ' + Math.round(fatti / totale * 100) + '%';
        prossimo();
      });
    }
    function fine() {
      if (!cercando) return;
      cercando = false;
      if (trovato) {
        $('#in-ip').value = trovato.ip;
        stato.textContent = '✓ Trovato «' + trovato.nome + '» (' + trovato.ip + '). Ora scrivi il PIN.';
        $('#in-pin').focus();
      } else stato.textContent = 'Non trovato. Scrivi l\'indirizzo che vedi sulla pagina di MassiStanze del PC.';
    }
    stato.textContent = 'Cerco…';
    for (var k = 0; k < 48; k++) prossimo();
  }

  // ------------------------------------------------------------ pannello del PC
  function pannelloPC() {
    var box = $('#pannello-pc');
    box.hidden = false;
    var ultimo = '';
    function aggiorna() {
      richiesta('GET', '/api/pc').then(function (d) {
        var firma = JSON.stringify([d.pin, d.ip, d.avvio, d.firewall, d.reti]);
        var reg = (d.registro || []).slice().reverse().join('\n') || '—';
        if (firma === ultimo) { var r = box.querySelector('.registro'); if (r) r.textContent = reg; return; }
        ultimo = firma;
        var url = 'http://' + d.ip[0] + ':' + d.porta;
        var qr = el('div', { class: 'qr' });
        try { var q = qrcode(0, 'M'); q.addData(url + '/?pin=' + d.pin); q.make(); qr.innerHTML = q.createSvgTag({ cellSize: 4, margin: 0, scalable: true }); } catch (e) {}
        var qrApk = el('div', { class: 'qr', style: 'width:110px;height:110px;flex:none;padding:8px' });
        try { var q2 = qrcode(0, 'L'); q2.addData(LINK_APK); q2.make(); qrApk.innerHTML = q2.createSvgTag({ cellSize: 3, margin: 0, scalable: true }); } catch (e) {}
        var avvio = el('input', { type: 'checkbox' }); avvio.checked = d.avvio;
        avvio.addEventListener('change', function () {
          richiesta('POST', '/api/pc/avvio', { attivo: avvio.checked }).then(function (r) { toast(r.avvio ? 'Partirà da solo all\'accensione del PC' : 'Avvio automatico tolto'); ultimo = ''; aggiorna(); });
        });
        var fw = d.firewall || {};
        var pubblica = (d.reti || []).some(function (r) { return r.tipo === 'Public'; });
        var problema = d.windows && (!fw.regola || fw.blocchi > 0);
        box.innerHTML = '';
        [
          d.windows ? el('div', { class: 'fw ' + (problema ? 'attenzione' : 'bene') }, problema ? [
            el('b', { text: '⚠️ Il firewall di Windows può bloccare i telefoni' }),
            el('div', { text: (fw.blocchi > 0 ? 'Windows ha un blocco per MassiStanze. ' : '') + (pubblica ? 'Il Wi-Fi è impostato come rete pubblica. ' : '') + 'Premi qui sotto e rispondi «Sì» alla finestra di Windows.' }),
            el('button', { class: 'pri', text: '🛡️ Sblocca nel firewall', onclick: function () {
              richiesta('POST', '/api/pc/firewall', {}).then(function (r) { toast(r.ok ? 'Fatto: ora riprova dal telefono' : 'Serve rispondere «Sì» alla finestra di Windows'); setTimeout(function () { ultimo = ''; aggiorna(); }, 2500); });
            } })
          ] : [el('span', { text: '✓ Firewall a posto: i telefoni possono collegarsi' })]) : null,
          el('h2', { text: '📡 Collega un telefono' }),
          el('div', { class: 'piccolo', text: 'Inquadra con la fotocamera (stesso Wi-Fi):' }),
          qr,
          el('div', { class: 'piccolo', text: 'oppure apri nel browser del telefono:' }),
          el('div', { class: 'grande', text: url.replace('http://', '') }),
          d.ip.length > 1 ? el('div', { class: 'piccolo', text: 'Altri indirizzi: ' + d.ip.slice(1).join(', ') }) : null,
          el('h3', { text: 'PIN' }),
          el('div', { class: 'riga' }, [el('div', { class: 'grande pin cresce', text: d.pin }),
            el('button', { class: 'sec', text: 'Nuovo PIN', style: 'padding:8px 12px', onclick: function () {
              conferma('Cambiare PIN? I telefoni collegati dovranno rimetterlo.', 'Cambia', function () { richiesta('POST', '/api/pc/nuovo-pin', {}).then(function () { ultimo = ''; aggiorna(); }); });
            } })]),
          el('h3', { text: 'App Android' }),
          el('div', { class: 'riga', style: 'align-items:flex-start' }, [qrApk, el('div', { class: 'piccolo', text: 'Inquadra per scaricare MassiStanze.apk (serve per gli annunci dal telefono). Va bene anche il browser, iPhone compreso.' })]),
          el('h3', { text: 'Questo PC' }),
          d.windows ? el('label', { class: 'interr' }, [el('span', { text: 'Avvia con Windows' }), avvio]) : null,
          el('div', { class: 'piccolo', text: 'Il PC suona finché questa pagina è aperta. Musica salvata in: ' + d.dati }),
          el('h3', { text: 'Ultime attività' }),
          el('div', { class: 'registro', text: reg }),
          el('div', { class: 'bottoni' }, [el('button', { class: 'peri', text: '⏻ Spegni MassiStanze', onclick: function () {
            conferma('Spegnere MassiStanze? La musica si ferma in tutte le stanze.', 'Spegni', function () {
              richiesta('POST', '/api/pc/chiudi', {}).then(function () { document.body.innerHTML = '<p style="padding:40px;font-size:18px">MassiStanze è spento. Puoi chiudere questa pagina.</p>'; });
            });
          } })])
        ].forEach(function (x) { if (x) box.appendChild(x); });
      }).catch(function () {});
    }
    aggiorna();
    setInterval(aggiorna, 3000);
  }

  // ------------------------------------------------------------ avvio
  function avvia() {
    Array.prototype.forEach.call(document.querySelectorAll('#schede button'), function (b) { b.addEventListener('click', function () { vai(b.dataset.v); }); });
    $('#mini').addEventListener('click', function () { vai('onda'); });
    $('#b-impost').addEventListener('click', impostazioni);
    $('#velo').addEventListener('click', chiudiFoglio);
    $('#sblocca').addEventListener('click', sbloccaAudio);
    $('#b-collega').addEventListener('click', function () { collega($('#in-pin').value.trim()); });
    $('#in-pin').addEventListener('keydown', function (e) { if (e.key === 'Enter') collega($('#in-pin').value.trim()); });
    $('#b-cerca').addEventListener('click', cercaPC);
    document.addEventListener('keydown', function (e) { if (e.key === 'Escape' && foglioAperto) chiudiFoglio(); });
    // il primo tocco ovunque sblocca l'audio (regola dei browser)
    var primo = function () { if (collegato && mia().attivo !== false) sbloccaAudio(); };
    document.addEventListener('pointerdown', primo, { once: true });

    impostaCollegato(false);
    schermoAcceso();
    vai(st.vista || 'onda');
    if (SUL_PC) {
      document.body.classList.add('sul-pc');
      pannelloPC();
      apriWS();
    } else {
      var m = location.search.match(/[?&]pin=(\d{4})/);
      if (m && !APP) {
        history.replaceState(null, '', location.pathname);
        $('#in-pin').value = m[1];
        chiediPin();
        collega(m[1]);
      } else if (!st.token || (APP && !st.base)) chiediPin();
      else apriWS();
    }
  }

  if (APP && window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.App) {
    window.Capacitor.Plugins.App.addListener('backButton', function () {
      if (foglioAperto) chiudiFoglio();
      else if (vista !== 'onda') vai('onda');
      else window.Capacitor.Plugins.App.minimizeApp();
    });
  }

  avvia();
  window.MassiStanze = { stato: function () { return { S: S, st: st, scarto: scarto, sincro: sincro, ctx: ctx && ctx.state, programmati: programmati.length, mappa: mappa }; } };
})();
