/* Source: Reynard iOS FxA WebChannel bridge, MPL-2.0. */
"use strict";

// Only injected into top-level https://accounts.firefox.com pages.
// Never send credentials, tokens, or arbitrary browser content to the host.
(function () {
  const nativeApp = "reynardFxaWebChannel";
  let port;
  function connect() {
    if (port) return port;
    port = browser.runtime.connectNative(nativeApp);
    port.onMessage.addListener(message => {
      window.dispatchEvent(new CustomEvent("WebChannelMessageToContent", {
        detail: JSON.stringify(message)
      }));
    });
    port.onDisconnect.addListener(() => { port = null; });
    return port;
  }
  window.addEventListener("WebChannelMessageToChrome", event => {
    let detail;
    try {
      detail = typeof event.detail === "string" ?
        JSON.parse(event.detail) : JSON.parse(JSON.stringify(event.detail));
    } catch (_) { return; }
    if (!detail || detail.id !== "account_updates" || !detail.message) return;
    connect().postMessage(detail);
  }, true);
  connect();
})();
