if (typeof AndroidBridge !== 'undefined') {
  const listeners = [];
  window.chrome = {
    storage: { onChanged: { addListener: fn => listeners.push(fn) } },
    runtime: {
      sendMessage: async (msg) => {
        const res = JSON.parse(AndroidBridge.handleMessage(msg.type));
        if (res.ok) listeners.forEach(fn => fn({state: {newValue: res.state}}));
        return res;
      }
    }
  };
  window.__notifyStateUpdate = (s) => listeners.forEach(fn => fn({state: {newValue: s}}));
}
