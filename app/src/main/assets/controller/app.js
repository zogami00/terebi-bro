/* Terebi Bro controller. No inline HTML injection: all server data is written
   with textContent, never innerHTML. */
(function () {
  'use strict';

  var TOKEN_KEY = 'terebi.token';
  var CSRF_HEADER = 'X-Terebi-CSRF';
  var BACKOFF_MIN_MS = 1000;
  var BACKOFF_MAX_MS = 30000;
  var PING_INTERVAL_MS = 20000;
  var PORT_NAV_DELAY_MS = 2000;
  var PORT_NAV_RETRY_DELAY_MS = 1500;
  var PORT_NAV_MAX_ATTEMPTS = 4;

  var token = null;
  var port = window.location.port || '80';
  var socket = null;
  var reconnectTimer = null;
  var reconnectSuspended = false;
  var attempt = 0;
  var pingTimer = null;
  var bannerTimer = null;

  function $(id) { return document.getElementById(id); }

  function show(el) { el.classList.remove('hidden'); }
  function hide(el) { el.classList.add('hidden'); }

  function showError(message) {
    var el = $('pairError');
    if (el) el.textContent = message || '';
  }

  function banner(message) {
    var el = $('errorBanner');
    el.textContent = message || '';
    if (message) { show(el); } else { hide(el); }
    if (bannerTimer) { clearTimeout(bannerTimer); bannerTimer = null; }
    if (message) {
      bannerTimer = setTimeout(function () { hide(el); }, 6000);
    }
  }

  function api(method, path, body) {
    var headers = {};
    headers[CSRF_HEADER] = '1';
    if (token) headers['Authorization'] = 'Bearer ' + token;
    var options = { method: method, headers: headers, cache: 'no-store' };
    if (body !== undefined) {
      headers['Content-Type'] = 'application/json';
      options.body = JSON.stringify(body);
    }
    return fetch(path, options).then(function (response) {
      return response.json().catch(function () { return null; }).then(function (json) {
        // A bad PIN on /api/pair is a 401 too, but it must not log the user out.
        if (response.status === 401 && path !== '/api/pair') { logout(); }
        return { status: response.status, json: json };
      });
    }).catch(function () {
      return { status: 0, json: null };
    });
  }

  function apiError(result) {
    if (result.json && result.json.error) return result.json.error.message || result.json.error.code;
    return 'Request failed (' + result.status + ')';
  }

  /* ---------------------------------------------------------------- pairing */

  function pair() {
    var pin = $('pairPin').value.trim();
    var name = $('pairName').value.trim() || 'controller';
    showError('');
    api('POST', '/api/pair', { pin: pin, clientName: name }).then(function (result) {
      if (result.status === 200 && result.json && result.json.token) {
        token = result.json.token;
        localStorage.setItem(TOKEN_KEY, token);
        hide($('pairingScreen'));
        startSession();
      } else {
        showError(apiError(result));
      }
    });
  }

  function logout() {
    token = null;
    localStorage.removeItem(TOKEN_KEY);
    stopPing();
    reconnectSuspended = true;
    if (socket) { try { socket.close(); } catch (e) { /* ignore */ } }
    socket = null;
    if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; }
    hide($('mainScreen'));
    show($('pairingScreen'));
    setOnline(false);
  }

  /* A port change navigates to a different origin. localStorage is scoped to an
     origin, so the token is carried across in the URL fragment (never sent to
     the server) and moved into localStorage immediately. An invalid token then
     falls back to the pairing screen through the normal auth path. */
  function adoptTokenFromHash() {
    var match = /^#t=([A-Za-z0-9_-]{1,512})$/.exec(window.location.hash || '');
    if (!match) return null;
    if (window.history && window.history.replaceState) {
      window.history.replaceState(null, '', window.location.pathname + window.location.search);
    }
    return match[1];
  }

  /* -------------------------------------------------------------- websocket */

  function connect() {
    if (!token || reconnectSuspended) return;
    var url = (window.location.protocol === 'https:' ? 'wss://' : 'ws://') + window.location.host + '/ws';
    var thisSocket;
    try {
      thisSocket = new WebSocket(url);
    } catch (e) {
      scheduleReconnect();
      return;
    }
    socket = thisSocket;

    thisSocket.onopen = function () {
      if (socket !== thisSocket) return;
      attempt = 0;
      setOnline(true);
      send({ type: 'auth', token: token });
      startPing();
    };

    thisSocket.onmessage = function (event) {
      if (socket !== thisSocket) return;
      var message;
      try { message = JSON.parse(event.data); } catch (e) { return; }
      handleMessage(message);
    };

    thisSocket.onclose = function (event) {
      // Ignore a late close from a socket that has already been replaced.
      if (socket !== thisSocket) return;
      setOnline(false);
      stopPing();
      // Only revocation/auth failure means the token is gone; a TV shutdown or
      // auth timeout must keep the token and retry with backoff.
      var unauthorized = event.code === 4401 &&
        (event.reason === 'revoked' || event.reason === 'unauthorized');
      if (unauthorized) { logout(); return; }
      if (!reconnectSuspended) scheduleReconnect();
    };

    thisSocket.onerror = function () { /* onclose follows */ };
  }

  function send(payload) {
    if (socket && socket.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify(payload));
    }
  }

  function scheduleReconnect() {
    if (reconnectSuspended) return;
    var base = Math.min(BACKOFF_MAX_MS, BACKOFF_MIN_MS * Math.pow(2, attempt));
    var jitter = base * 0.2 * (Math.random() * 2 - 1);
    var delay = Math.max(BACKOFF_MIN_MS, Math.round(base + jitter));
    attempt++;
    if (reconnectTimer) clearTimeout(reconnectTimer);
    reconnectTimer = setTimeout(connect, delay);
  }

  function startPing() {
    stopPing();
    pingTimer = setInterval(function () {
      send({ type: 'ping', t: Date.now() });
    }, PING_INTERVAL_MS);
  }

  function stopPing() {
    if (pingTimer) { clearInterval(pingTimer); pingTimer = null; }
  }

  function handleMessage(message) {
    if (message.type === 'auth_ok') {
      hide($('pairingScreen'));
      show($('mainScreen'));
      if (message.state) updateState(message.state);
      refreshDevice();
    } else if (message.type === 'state') {
      updateState(message.state);
    } else if (message.type === 'event') {
      handleEvent(message);
    } else if (message.type === 'error') {
      banner(message.message || message.code || 'Error');
    }
  }

  function handleEvent(message) {
    if (message.name === 'page_error') {
      banner('The TV browser could not load the page.');
    } else if (message.name === 'webview_restarted') {
      banner('WebView restarted.');
    } else if (message.name === 'port_changing') {
      var nextPort = String(message.detail || port);
      banner('Server port changed to ' + nextPort + '. Reconnecting…');
      port = nextPort;
      reconnectSuspended = true;
      if (socket) { try { socket.close(4000, 'port_change'); } catch (e) { /* ignore */ } }
      socket = null;
      if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; }
      schedulePortNavigation(nextPort, 0);
    }
  }

  function portTargetUrl(nextPort) {
    var target = window.location.protocol + '//' + window.location.hostname + ':' + nextPort + '/';
    // Carry the token across the origin change in the fragment.
    if (token) target += '#t=' + encodeURIComponent(token);
    return target;
  }

  /* The page must move to the new origin, otherwise relative API paths and the
     CSP connect-src rule would point at the old port and fail. The server
     rebinds shortly after announcing the change, so the first attempt is
     delayed well past that; if the navigation has not committed by then the new
     origin was not reachable and the attempt is retried a bounded number of
     times before giving up. */
  function schedulePortNavigation(nextPort, attempt) {
    var delay = attempt === 0 ? PORT_NAV_DELAY_MS : PORT_NAV_RETRY_DELAY_MS;
    setTimeout(function () {
      var next = attempt + 1;
      try {
        window.location.replace(portTargetUrl(nextPort));
      } catch (e) { /* ignore */ }
      if (next < PORT_NAV_MAX_ATTEMPTS) {
        schedulePortNavigation(nextPort, next);
      } else {
        banner('Could not reach the TV on port ' + nextPort + '. Open the controller again once it is back.');
      }
    }, delay);
  }

  function setOnline(online) {
    $('statusDot').className = online ? 'dot online' : 'dot offline';
    $('statusText').textContent = online ? 'Online' : 'Offline';
  }

  /* ------------------------------------------------------------------- state */

  function setText(id, value) {
    var el = $(id);
    if (el) el.textContent = value === undefined || value === null ? '' : String(value);
  }

  function setValueIfIdle(id, value) {
    var el = $(id);
    if (el && document.activeElement !== el) el.value = value === undefined || value === null ? '' : String(value);
  }

  function updateState(state) {
    if (!state) return;
    setText('statusDevice', state.deviceName);
    setText('currentUrl', state.url);
    setText('currentTitle', state.title);
    setText('loadingText', state.loading ? 'yes (' + state.progress + '%)' : 'no');
    setText('networkText', state.network);
    setValueIfIdle('urlInput', state.url);
    setValueIfIdle('homeUrlInput', state.homeUrl);
    setValueIfIdle('deviceNameInput', state.deviceName);
    setValueIfIdle('portInput', state.port);
    var fullscreen = $('fullscreenToggle');
    if (fullscreen && document.activeElement !== fullscreen) fullscreen.checked = !!state.fullscreen;
    var keepAwake = $('keepAwakeToggle');
    if (keepAwake && document.activeElement !== keepAwake) keepAwake.checked = !!state.keepAwake;
  }

  function refreshDevice() {
    api('GET', '/api/device', undefined).then(function (result) {
      if (result.status !== 200 || !result.json) return;
      var d = result.json;
      setText('infoDeviceName', d.deviceName);
      setText('infoAppVersion', d.appVersion);
      setText('infoAndroidVersion', d.androidVersion);
      setText('infoSdk', d.sdkInt);
      setText('infoWebView', d.webViewVersion);
      setText('infoIp', d.ip);
      setText('infoPort', d.port);
      setText('infoPaired', d.pairedCount);
      setWebViewWarning(d.webViewOutdated, d.webViewVersion);
    });
  }

  /* The major component of the trailing token of a WebView version string
     (e.g. "com.google.android.webview 90.0.4430.91" -> "90"). Display-only;
     returns null when it cannot be read. */
  function webViewMajor(version) {
    if (!version) return null;
    var parts = String(version).trim().split(/\s+/);
    var match = /^(\d+)/.exec(parts[parts.length - 1] || '');
    return match ? match[1] : null;
  }

  function setWebViewWarning(outdated, version) {
    var el = $('webViewWarning');
    if (!el) return;
    if (!outdated) {
      el.textContent = '';
      hide(el);
      return;
    }
    var major = webViewMajor(version);
    var label = major ? ' (v' + major + ')' : '';
    el.textContent = 'The TV\u2019s WebView is out of date' + label +
      '. Modern websites may render incorrectly. ' +
      'Update "Android System WebView" / update the device.';
    show(el);
  }

  /* ------------------------------------------------------------------ actions */

  function navigate(path, body, method) {
    return api(method || 'POST', path, body === undefined ? {} : body);
  }

  function runAction(path, body) {
    return navigate(path, body).then(function (result) {
      if (result.status >= 400) banner(apiError(result));
      return result;
    });
  }

  function openUrl(setHome) {
    var url = $('urlInput').value.trim();
    if (!url) return;
    runAction('/api/nav/open', { url: url, setHome: !!setHome });
  }

  function saveHomeUrl() {
    var url = $('homeUrlInput').value.trim();
    if (!url) return;
    runAction('/api/home', { url: url });
  }

  function saveDeviceName() {
    var name = $('deviceNameInput').value.trim();
    if (!name) return;
    runAction('/api/settings/device-name', { name: name }).then(refreshDevice);
  }

  function savePort() {
    var value = parseInt($('portInput').value, 10);
    if (!value || value < 1024 || value > 65535) {
      banner('Port must be between 1024 and 65535.');
      return;
    }
    runAction('/api/settings/port', { port: value });
  }

  function saveDisplay() {
    runAction('/api/display', {
      fullscreen: $('fullscreenToggle').checked,
      keepAwake: $('keepAwakeToggle').checked
    });
  }

  function unpair() {
    api('POST', '/api/unpair', {}).then(function () { logout(); });
  }

  function wire() {
    $('pairButton').addEventListener('click', pair);
    $('pairPin').addEventListener('keydown', function (e) { if (e.key === 'Enter') pair(); });
    $('pairName').addEventListener('keydown', function (e) { if (e.key === 'Enter') pair(); });

    $('openButton').addEventListener('click', function () { openUrl(false); });
    $('openHomeButton').addEventListener('click', function () { openUrl(true); });

    $('navBack').addEventListener('click', function () { runAction('/api/nav/back'); });
    $('navForward').addEventListener('click', function () { runAction('/api/nav/forward'); });
    $('navHome').addEventListener('click', function () { runAction('/api/nav/home'); });
    $('navReload').addEventListener('click', function () { runAction('/api/nav/reload'); });
    $('navStop').addEventListener('click', function () { runAction('/api/nav/stop'); });

    $('dpadUp').addEventListener('click', function () { runAction('/api/dpad', { key: 'up' }); });
    $('dpadDown').addEventListener('click', function () { runAction('/api/dpad', { key: 'down' }); });
    $('dpadLeft').addEventListener('click', function () { runAction('/api/dpad', { key: 'left' }); });
    $('dpadRight').addEventListener('click', function () { runAction('/api/dpad', { key: 'right' }); });
    $('dpadOk').addEventListener('click', function () { runAction('/api/dpad', { key: 'ok' }); });
    $('dpadBack').addEventListener('click', function () { runAction('/api/dpad', { key: 'back' }); });

    $('restartWebview').addEventListener('click', function () { runAction('/api/webview/restart'); });
    $('clearCache').addEventListener('click', function () { runAction('/api/webview/clear-cache'); });
    $('clearSiteData').addEventListener('click', function () { runAction('/api/webview/clear-site-data'); });

    $('saveHomeUrl').addEventListener('click', saveHomeUrl);
    $('saveDeviceName').addEventListener('click', saveDeviceName);
    $('savePort').addEventListener('click', savePort);
    $('fullscreenToggle').addEventListener('change', saveDisplay);
    $('keepAwakeToggle').addEventListener('change', saveDisplay);

    $('unpairButton').addEventListener('click', unpair);
  }

  function startSession() {
    // A previous bad PIN or logout may have suspended reconnection; clear it.
    reconnectSuspended = false;
    attempt = 0;
    show($('mainScreen'));
    connect();
    refreshDevice();
    api('GET', '/api/state', undefined).then(function (result) {
      if (result.status === 200 && result.json && result.json.state) updateState(result.json.state);
    });
  }

  /* --------------------------------------------------------------------- init */

  function init() {
    wire();
    setOnline(false);
    var carried = adoptTokenFromHash();
    if (carried) {
      token = carried;
      localStorage.setItem(TOKEN_KEY, token);
    } else {
      token = localStorage.getItem(TOKEN_KEY);
    }
    if (token) {
      startSession();
    } else {
      show($('pairingScreen'));
      $('pairPin').focus();
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
