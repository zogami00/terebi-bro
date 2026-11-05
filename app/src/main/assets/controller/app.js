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
  var networks = [];
  var mqttPasswordSet = false;

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
      refreshNetworks();
      refreshMqtt();
      refreshMedia();
      refreshLibrary();
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
    setNetworks(state.extraNetworks);
    var fullscreen = $('fullscreenToggle');
    if (fullscreen && document.activeElement !== fullscreen) fullscreen.checked = !!state.fullscreen;
    var keepAwake = $('keepAwakeToggle');
    if (keepAwake && document.activeElement !== keepAwake) keepAwake.checked = !!state.keepAwake;
    if (state.mediaMode) setMediaModeLine(state.mediaMode);
    if (state.mediaSource !== undefined) setMediaSourceLine(state);
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
      setText('infoMqtt', d.mqttConnected ? 'connected' : 'disconnected');
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

  /* ------------------------------------------------- trusted networks (bootstrap) */

  /* Client-side mirror of the server's rule: dotted-quad IPv4 with a /8../32
     prefix. The server validates again and is authoritative. */
  function validCidr(value) {
    var match = /^([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})\/([0-9]{1,2})$/.exec(value);
    if (!match) return false;
    for (var i = 1; i <= 4; i++) {
      if (parseInt(match[i], 10) > 255) return false;
    }
    var prefix = parseInt(match[5], 10);
    return prefix >= 8 && prefix <= 32;
  }

  /* Server-provided values only ever reach the DOM through textContent. */
  function renderNetworks(list) {
    networks = (list || []).slice();
    var ul = $('networkList');
    if (!ul) return;
    while (ul.firstChild) ul.removeChild(ul.firstChild);
    if (networks.length === 0) {
      var empty = document.createElement('li');
      empty.className = 'muted';
      empty.textContent = 'No extra networks. Only the TV\u2019s own subnet can reach the controller.';
      ul.appendChild(empty);
      return;
    }
    networks.forEach(function (net) {
      var li = document.createElement('li');
      var span = document.createElement('span');
      span.textContent = net;
      var button = document.createElement('button');
      button.className = 'danger';
      button.textContent = 'Remove';
      button.addEventListener('click', function () { removeNetwork(net); });
      li.appendChild(span);
      li.appendChild(button);
      ul.appendChild(li);
    });
  }

  /* Rebuild only when the list actually changed, so a state broadcast during a
     page load never yanks the list out from under the user. */
  function setNetworks(list) {
    var next = list || [];
    if (next.join('|') === networks.join('|')) return;
    renderNetworks(next);
  }

  function refreshNetworks() {
    api('GET', '/api/settings/networks', undefined).then(function (result) {
      if (result.status === 200 && result.json && result.json.networks) {
        renderNetworks(result.json.networks);
      }
    });
  }

  function addNetwork() {
    var input = $('networkInput');
    var error = $('networkError');
    var value = input.value.trim();
    if (!validCidr(value)) {
      error.textContent = 'Enter an IPv4 CIDR such as 10.10.1.0/24 (prefix /8 to /32).';
      return;
    }
    error.textContent = '';
    var next = networks.slice();
    if (next.indexOf(value) < 0) next.push(value);
    api('POST', '/api/settings/networks', { networks: next }).then(function (result) {
      if (result.status === 200 && result.json && result.json.networks) {
        renderNetworks(result.json.networks);
        input.value = '';
      } else {
        error.textContent = apiError(result);
      }
    });
  }

  function removeNetwork(value) {
    var next = networks.filter(function (net) { return net !== value; });
    api('POST', '/api/settings/networks', { networks: next }).then(function (result) {
      if (result.status === 200 && result.json && result.json.networks) {
        renderNetworks(result.json.networks);
      } else {
        banner(apiError(result));
      }
    });
  }

  /* --------------------------------------------------------------------- mqtt */

  /* Connection state line. Server-provided values only ever reach the DOM
     through textContent. */
  function setMqttStatus(data) {
    var el = $('mqttState');
    if (!el) return;
    if (!data || data.enabled !== true) {
      el.textContent = 'MQTT: disabled';
      return;
    }
    var state = data.mqttState || (data.mqttConnected ? 'connected' : 'disconnected');
    var text = 'MQTT: ' + state;
    if (!data.mqttConnected && data.mqttLastError) text += ' \u2014 ' + data.mqttLastError;
    // A partially published Home Assistant discovery pass is visible here too,
    // so a missing entity can be diagnosed without a logcat.
    if (data.mqttConnected && data.mqttDiscoveryError) {
      text += ' \u2014 discovery: ' + data.mqttDiscoveryError;
    }
    el.textContent = text;
  }

  function refreshMqtt() {
    api('GET', '/api/settings/mqtt', undefined).then(function (result) {
      if (result.status !== 200 || !result.json) return;
      var d = result.json;
      mqttPasswordSet = !!d.mqttPasswordSet;
      setValueIfIdle('mqttHostInput', d.host);
      setValueIfIdle('mqttPortInput', d.port);
      setValueIfIdle('mqttUsernameInput', d.username);
      setValueIfIdle('mqttBaseTopicInput', d.baseTopic);
      setValueIfIdle('mqttDiscoveryPrefixInput', d.discoveryPrefix);
      var enabled = $('mqttEnabledToggle');
      if (enabled && document.activeElement !== enabled) enabled.checked = !!d.enabled;
      var tls = $('mqttTlsToggle');
      if (tls && document.activeElement !== tls) tls.checked = !!d.tls;
      var discovery = $('mqttDiscoveryToggle');
      if (discovery && document.activeElement !== discovery) discovery.checked = !!d.discoveryEnabled;
      var pw = $('mqttPasswordInput');
      if (pw) pw.placeholder = mqttPasswordSet ? 'leave blank to keep' : 'no password set';
      setMqttStatus(d);
    });
  }

  function saveMqtt() {
    var error = $('mqttError');
    error.textContent = '';
    var enabled = $('mqttEnabledToggle').checked;
    var host = $('mqttHostInput').value.trim();
    if (enabled && !host) {
      error.textContent = 'A broker host is required when MQTT is enabled.';
      return;
    }
    var port = parseInt($('mqttPortInput').value, 10);
    if (!port || port < 1 || port > 65535) {
      error.textContent = 'Port must be between 1 and 65535.';
      return;
    }
    var body = {
      enabled: enabled,
      host: host,
      port: port,
      tls: $('mqttTlsToggle').checked,
      username: $('mqttUsernameInput').value,
      baseTopic: $('mqttBaseTopicInput').value.trim(),
      discoveryEnabled: $('mqttDiscoveryToggle').checked,
      discoveryPrefix: $('mqttDiscoveryPrefixInput').value.trim()
    };
    // Password is write-only: send it only when the field is non-empty so an
    // untouched field never clears a stored password.
    var password = $('mqttPasswordInput').value;
    if (password !== '') body.password = password;
    api('POST', '/api/settings/mqtt', body).then(function (result) {
      if (result.status === 200 && result.json) {
        $('mqttPasswordInput').value = '';
        refreshMqtt();
      } else {
        error.textContent = apiError(result);
      }
    });
  }

  function clearMqttPassword() {
    api('POST', '/api/settings/mqtt', { password: '' }).then(function (result) {
      if (result.status === 200) {
        $('mqttPasswordInput').value = '';
        refreshMqtt();
      } else {
        banner(apiError(result));
      }
    });
  }

  /* -------------------------------------------------------------------- media */

  function setCheckIfIdle(id, value) {
    var el = $(id);
    if (el && document.activeElement !== el) el.checked = !!value;
  }

  function setSelectIfIdle(id, value) {
    var el = $(id);
    if (el && document.activeElement !== el) el.value = value === undefined || value === null ? '' : String(value);
  }

  function setMediaModeLine(mode) {
    setText('mediaModeState', 'Mode: ' + (mode || 'qms_only'));
  }

  var MEDIA_FIELD_IDS = {
    mediaModeEnabled: 'mediaEnabledToggle',
    mediaSplitPercent: 'mediaSplitInput',
    headerBandPercent: 'mediaHeaderInput',
    stripHeightPercent: 'mediaStripInput',
    dwellSec: 'mediaDwellInput',
    volumePercent: 'mediaVolumeInput',
    imageDurationSec: 'mediaImageDurationInput',
    mediaFillMode: 'mediaFillModeInput',
    uiLanguage: 'mediaLanguageInput',
    uiScale: 'mediaUiScaleInput',
    qmsUrl: 'mediaQmsUrlInput',
    qmsStripSelector: 'mediaStripSelectorInput',
    qmsNumberSelector: 'mediaNumberSelectorInput',
    qmsLocationSelector: 'mediaLocationSelectorInput',
    qmsTimeSelector: 'mediaTimeSelectorInput',
    callSource: 'mediaCallSourceInput',
    playlistUrl: 'playlistUrlInput',
    playlistRefreshSec: 'playlistRefreshInput',
    mediaWebUrl: 'mediaWebUrlInput',
    mediaWebEnabled: 'mediaWebEnabledToggle',
    mediaSourceDefault: 'mediaSourceDefaultInput'
  };

  function clearMediaFieldErrors() {
    var notes = document.querySelectorAll('.media-field-error');
    for (var i = 0; i < notes.length; i++) notes[i].parentNode.removeChild(notes[i]);
  }

  /* The server names the offending field as "field: reason"; place that text
     directly after the input it belongs to, falling back to the card error. */
  function showMediaError(message) {
    clearMediaFieldErrors();
    var error = $('mediaError');
    error.textContent = '';
    var text = message || 'Request failed';
    var match = /^([A-Za-z]+):/.exec(text);
    var id = match ? MEDIA_FIELD_IDS[match[1]] : null;
    var input = id ? $(id) : null;
    if (!input || !input.parentNode) {
      error.textContent = text;
      return;
    }
    var note = document.createElement('p');
    note.className = 'error media-field-error';
    note.textContent = text;
    input.parentNode.insertBefore(note, input.nextSibling);
  }

  function refreshMedia() {
    api('GET', '/api/settings/media', undefined).then(function (result) {
      if (result.status !== 200 || !result.json) return;
      var d = result.json;
      var runtime = d.runtime || {};
      setCheckIfIdle('mediaEnabledToggle', d.mediaModeEnabled);
      setValueIfIdle('mediaSplitInput', d.mediaSplitPercent);
      setValueIfIdle('mediaHeaderInput', d.headerBandPercent);
      setValueIfIdle('mediaStripInput', d.stripHeightPercent);
      setValueIfIdle('mediaDwellInput', d.dwellSec);
      setValueIfIdle('mediaVolumeInput', d.volumePercent);
      setValueIfIdle('mediaImageDurationInput', d.imageDurationSec);
      setSelectIfIdle('mediaFillModeInput', d.mediaFillMode);
      setSelectIfIdle('mediaLanguageInput', d.uiLanguage);
      setValueIfIdle('mediaUiScaleInput', d.uiScale);
      setValueIfIdle('mediaQmsUrlInput', d.qmsUrl);
      setValueIfIdle('mediaStripSelectorInput', d.qmsStripSelector);
      setValueIfIdle('mediaNumberSelectorInput', d.qmsNumberSelector);
      setValueIfIdle('mediaLocationSelectorInput', d.qmsLocationSelector);
      setValueIfIdle('mediaTimeSelectorInput', d.qmsTimeSelector);
      setSelectIfIdle('mediaCallSourceInput', d.callSource);
      setCheckIfIdle('playlistEnabledToggle', d.playlistEnabled);
      setValueIfIdle('playlistUrlInput', d.playlistUrl);
      setValueIfIdle('playlistRefreshInput', d.playlistRefreshSec);
      // The stored page URL is only ever returned masked, so it is shown as a
      // placeholder instead of a value: an untouched field posts nothing and
      // the server keeps the stored URL (the MQTT password's convention).
      var webUrlField = $('mediaWebUrlInput');
      if (webUrlField) {
        webUrlField.placeholder = d.mediaWebUrl || 'https://jellyfin.example.com/web';
      }
      setCheckIfIdle('mediaWebEnabledToggle', d.mediaWebEnabled);
      setSelectIfIdle('mediaSourceDefaultInput', d.mediaSourceDefault);
      setMediaModeLine(runtime.mode);
      setMediaSourceLine(runtime);
    });
  }

  /* Read-only source diagnostics, fed by both the state broadcast and the
     settings runtime object. A server-provided string only ever reaches the
     DOM through textContent. */
  function setMediaSourceLine(source) {
    var el = $('mediaSourceState');
    if (!el || !source) return;
    var name = source.mediaSource ? source.mediaSource : 'none';
    var count = Number(source.mediaCount) || 0;
    var fetchedMs = Number(source.playlistLastFetchMs) || 0;
    var error = source.playlistLastError;
    // The count is what makes an empty queue obvious without opening the
    // library: "local · 0 items" beside the "media unavailable" band.
    var parts = [name + ' \u00b7 ' + count + (count === 1 ? ' item' : ' items')];
    parts.push(fetchedMs > 0
      ? 'last playlist fetch: ' + Math.round(fetchedMs) + ' ms'
      : 'playlist not fetched yet');
    if (error) parts.push('playlist error: ' + error);
    el.textContent = parts.join(' \u2014 ');
  }

  /* A number input is either a valid integer or omitted from the payload. An
     explicit null would be a strict-parser type error naming no field. */
  function putNumber(body, key, id) {
    var text = String($(id).value).trim();
    if (text === '') return;
    var value = parseInt(text, 10);
    if (isFinite(value)) body[key] = value;
  }

  function putString(body, key, id) {
    var value = $(id).value;
    if (value !== '') body[key] = value;
  }

  /* The whole media block is one request: a partial update is validated against
     the merged settings on the server, so the band sum is checked as a whole.
     Empty inputs are left out rather than sent as null. */
  function saveMedia() {
    var error = $('mediaError');
    error.textContent = '';
    clearMediaFieldErrors();
    var body = { mediaModeEnabled: $('mediaEnabledToggle').checked };
    putNumber(body, 'mediaSplitPercent', 'mediaSplitInput');
    putNumber(body, 'headerBandPercent', 'mediaHeaderInput');
    putNumber(body, 'stripHeightPercent', 'mediaStripInput');
    putNumber(body, 'dwellSec', 'mediaDwellInput');
    putNumber(body, 'volumePercent', 'mediaVolumeInput');
    putNumber(body, 'imageDurationSec', 'mediaImageDurationInput');
    putNumber(body, 'uiScale', 'mediaUiScaleInput');
    putString(body, 'mediaFillMode', 'mediaFillModeInput');
    putString(body, 'uiLanguage', 'mediaLanguageInput');
    // Selectors are omitted when blank: the server keeps the previous value,
    // and a blank selector is a named 400 rather than a silent reset.
    putString(body, 'qmsStripSelector', 'mediaStripSelectorInput');
    putString(body, 'qmsNumberSelector', 'mediaNumberSelectorInput');
    putString(body, 'qmsLocationSelector', 'mediaLocationSelectorInput');
    putString(body, 'qmsTimeSelector', 'mediaTimeSelectorInput');
    putString(body, 'callSource', 'mediaCallSourceInput');
    // The QMS URL is always sent, even empty: that is the documented way to
    // clear a stale URL back to "track the Home URL", exactly like playlistUrl.
    body.qmsUrl = $('mediaQmsUrlInput').value.trim();
    // The playlist URL is always sent, not omitted when empty: that is the
    // documented way to clear a stale URL, and enabling the playlist without
    // one is a named 400 rather than a silent no-op.
    body.playlistEnabled = $('playlistEnabledToggle').checked;
    body.playlistUrl = $('playlistUrlInput').value.trim();
    putNumber(body, 'playlistRefreshSec', 'playlistRefreshInput');
    // Leave blank to keep the stored URL: GET only returns it masked, so an
    // untouched field must not overwrite it. A `web` startup source with no
    // stored URL is still a named 400.
    var webUrl = $('mediaWebUrlInput').value.trim();
    if (webUrl) body.mediaWebUrl = webUrl;
    body.mediaWebEnabled = $('mediaWebEnabledToggle').checked;
    body.mediaSourceDefault = $('mediaSourceDefaultInput').value;
    api('POST', '/api/settings/media', body).then(function (result) {
      if (result.status === 200 && result.json) {
        clearMediaFieldErrors();
        refreshMedia();
      } else {
        showMediaError(apiError(result));
      }
    });
  }

  function requestMediaMode(mode) {
    api('POST', '/api/media/mode', { mode: mode }).then(function (result) {
      if (result.status === 200 && result.json) {
        setMediaModeLine(result.json.mode || mode);
      } else {
        banner(apiError(result));
      }
    });
  }

  /* The media WebView source: one deliberate page, not a queue item. The error
     stays next to the field instead of the banner so a bad URL is obvious
     without stealing the whole screen. */
  function openMediaWeb() {
    var input = $('mediaWebUrlInput');
    var error = $('mediaWebError');
    var url = String(input.value).trim();
    error.textContent = '';
    if (!url) {
      error.textContent = 'Enter a URL';
      return;
    }
    api('POST', '/api/media/open', { url: url, web: true }).then(function (result) {
      if (result.status === 200 && result.json) {
        error.textContent = '';
      } else {
        error.textContent = apiError(result);
      }
    });
  }

  /* ------------------------------------------------------------------ library */

  var libraryFiles = [];
  var previewUrl = null;
  var previewName = '';

  function formatBytes(bytes) {
    var n = Number(bytes) || 0;
    if (n < 1024) return n + ' B';
    var units = ['KB', 'MB', 'GB'];
    var i = -1;
    do { n = n / 1024; i++; } while (n >= 1024 && i < units.length - 1);
    return (n < 10 ? n.toFixed(1) : String(Math.round(n))) + ' ' + units[i];
  }

  function setUploadError(message) {
    var el = $('libraryError');
    if (el) el.textContent = message || '';
  }

  /* A warning only: the magic-byte gate accepted the file, but this device's
     metadata retriever could not read it. The upload is kept. */
  function setUploadWarning(message) {
    var el = $('libraryWarning');
    if (el) el.textContent = message || '';
  }

  function setUploadProgress(text) {
    var el = $('uploadProgress');
    if (el) el.textContent = text || '';
  }

  /* The preview is a Blob object URL; it is revoked as soon as it is replaced
     so a phone does not pin the decoded file in memory. */
  function clearPreview() {
    if (previewUrl) {
      URL.revokeObjectURL(previewUrl);
      previewUrl = null;
    }
    previewName = '';
    var box = $('libraryPreview');
    if (box) { while (box.firstChild) box.removeChild(box.firstChild); }
  }

  /* Server-derived values only ever reach the DOM through textContent. */
  function renderLibrary(files) {
    libraryFiles = (files || []).slice();
    var summary = $('librarySummary');
    if (summary) {
      summary.textContent = libraryFiles.length === 0
        ? 'No media yet.'
        : libraryFiles.length + ' file' + (libraryFiles.length === 1 ? '' : 's') + ' in the library.';
    }
    var ul = $('libraryList');
    if (!ul) return;
    while (ul.firstChild) ul.removeChild(ul.firstChild);
    libraryFiles.forEach(function (file) {
      var li = document.createElement('li');
      var info = document.createElement('span');
      info.className = 'grow';
      info.textContent = file.name + ' \u2014 ' + formatBytes(file.bytes) + ' (' + file.kind + ')';
      var preview = document.createElement('button');
      preview.textContent = 'Preview';
      preview.addEventListener('click', function () { previewFile(file); });
      var del = document.createElement('button');
      del.className = 'danger';
      del.textContent = 'Delete';
      del.addEventListener('click', function () { deleteLibraryFile(file.name); });
      li.appendChild(info);
      li.appendChild(preview);
      li.appendChild(del);
      ul.appendChild(li);
    });
  }

  function refreshLibrary() {
    api('GET', '/api/media/files', undefined).then(function (result) {
      if (result.status === 200 && result.json && result.json.files) {
        renderLibrary(result.json.files);
      }
    });
  }

  /* Forces the TV to re-read the media folder. An `adb push` while the app is
     in the foreground is otherwise invisible until the next resume, so this is
     the operator's way to make it appear without backgrounding the app. */
  function rescanLibrary() {
    setUploadError('');
    setUploadProgress('Reloading media\u2026');
    api('POST', '/api/media/rescan', {}).then(function (result) {
      if (result.status === 200 && result.json) {
        var count = Number(result.json.count) || 0;
        setUploadProgress('Media reloaded \u2014 ' + count + (count === 1 ? ' item.' : ' items.'));
        refreshLibrary();
        refreshMedia();
      } else {
        setUploadProgress('');
        setUploadError(apiError(result));
      }
    });
  }

  function deleteLibraryFile(name) {
    setUploadError('');
    api('POST', '/api/media/delete', { name: name }).then(function (result) {
      if (result.status === 200 && result.json && result.json.files) {
        if (previewName === name) clearPreview();
        renderLibrary(result.json.files);
      } else {
        setUploadError(apiError(result));
      }
    });
  }

  function previewFile(file) {
    setUploadError('');
    // Bearer token in a header, never in a URL: the preview is a fetch, not a
    // navigation, and no credential or filename reaches the address bar.
    fetch('/api/media/file', {
      method: 'GET',
      headers: {
        'Authorization': 'Bearer ' + token,
        'X-Terebi-CSRF': '1',
        // The server resolves the file from this header only; without it the
        // preview is a 404. The name never travels in the URL.
        'X-Terebi-Filename': encodeURIComponent(file.name)
      },
      cache: 'no-store'
    }).then(function (response) {
      if (!response.ok) throw new Error('Preview failed (' + response.status + ')');
      return response.blob();
    }).then(function (blob) {
      clearPreview();
      previewUrl = URL.createObjectURL(blob);
      previewName = file.name;
      var box = $('libraryPreview');
      var media;
      if (file.kind === 'video') {
        media = document.createElement('video');
        media.controls = true;
      } else {
        media = document.createElement('img');
        media.alt = file.name;
      }
      media.src = previewUrl;
      media.style.maxWidth = '100%';
      media.style.maxHeight = '240px';
      media.style.marginTop = '12px';
      media.style.borderRadius = '8px';
      box.appendChild(media);
    }).catch(function (error) {
      setUploadError(error && error.message ? error.message : 'Preview failed');
    });
  }

  /* One request per file, sequential, so the server's single-upload rule is
     never tripped by our own concurrency. The filename travels in the
     percent-encoded header, never in the URL. */
  function uploadFiles() {
    var input = $('libraryFileInput');
    var files = input && input.files ? input.files : null;
    setUploadError('');
    setUploadWarning('');
    if (!files || files.length === 0) {
      setUploadError('Choose at least one file to upload.');
      return;
    }
    var list = [];
    for (var i = 0; i < files.length; i++) list.push(files[i]);
    var index = 0;
    var firstError = '';
    var firstWarning = '';

    function next() {
      if (index >= list.length) {
        setUploadProgress('Upload finished.');
        input.value = '';
        refreshLibrary();
        return;
      }
      uploadOne(list[index++], function (message) {
        if (message && !firstError) firstError = message;
        if (firstError) setUploadError(firstError);
        next();
      });
    }

    function uploadOne(file, done) {
      var xhr = new XMLHttpRequest();
      xhr.open('POST', '/api/media/upload');
      xhr.setRequestHeader(CSRF_HEADER, '1');
      if (token) xhr.setRequestHeader('Authorization', 'Bearer ' + token);
      xhr.setRequestHeader('X-Terebi-Filename', encodeURIComponent(file.name));
      // A stalled connection must not hold the server's single upload slot for
      // ever; the server also releases it on every failure path.
      xhr.timeout = 120000;
      xhr.upload.onprogress = function (event) {
        if (event.lengthComputable && event.total > 0) {
          var pct = Math.round((event.loaded / event.total) * 100);
          setUploadProgress('Uploading ' + file.name + ' \u2014 ' + pct + '%');
        } else {
          setUploadProgress('Uploading ' + file.name + '\u2026');
        }
      };
      xhr.onload = function () {
        var json = null;
        try { json = JSON.parse(xhr.responseText); } catch (e) { json = null; }
        if (xhr.status === 200 && json && json.ok) {
          setUploadProgress('Uploaded ' + file.name + '.');
          // The server accepted the file on its magic bytes; a probe that is
          // not `readable` is a warning, never a rejection.
          if (json.probe && json.probe !== 'readable') {
            if (!firstWarning) {
              firstWarning = file.name + ': uploaded, but this device could not ' +
                'read its metadata \u2014 if it does not play, check the codec.';
            }
            setUploadWarning(firstWarning);
          }
          done('');
        } else {
          var message = json && json.error
            ? (json.error.message || json.error.code)
            : 'Upload failed (' + xhr.status + ')';
          done(file.name + ': ' + message);
        }
      };
      xhr.onerror = function () {
        done(file.name + ': upload failed');
      };
      xhr.ontimeout = function () {
        done(file.name + ': upload timed out');
      };
      xhr.send(file);
    }

    next();
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
    $('addNetwork').addEventListener('click', addNetwork);
    $('networkInput').addEventListener('keydown', function (e) { if (e.key === 'Enter') addNetwork(); });
    $('fullscreenToggle').addEventListener('change', saveDisplay);
    $('keepAwakeToggle').addEventListener('change', saveDisplay);

    $('saveMqtt').addEventListener('click', saveMqtt);
    $('clearMqttPassword').addEventListener('click', clearMqttPassword);

    $('saveMedia').addEventListener('click', saveMedia);
    $('mediaEnabledToggle').addEventListener('change', saveMedia);
    $('playlistEnabledToggle').addEventListener('change', saveMedia);
    $('openMediaWeb').addEventListener('click', openMediaWeb);
    $('mediaModeQms').addEventListener('click', function () { requestMediaMode('qms_only'); });
    $('mediaModeMedia').addEventListener('click', function () { requestMediaMode('media_only'); });
    $('mediaModeSplit').addEventListener('click', function () { requestMediaMode('split'); });

    $('uploadButton').addEventListener('click', uploadFiles);
    $('libraryReloadButton').addEventListener('click', rescanLibrary);

    $('unpairButton').addEventListener('click', unpair);
  }

  function startSession() {
    // A previous bad PIN or logout may have suspended reconnection; clear it.
    reconnectSuspended = false;
    attempt = 0;
    show($('mainScreen'));
    connect();
    refreshDevice();
    refreshNetworks();
    refreshMqtt();
    refreshMedia();
    refreshLibrary();
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
