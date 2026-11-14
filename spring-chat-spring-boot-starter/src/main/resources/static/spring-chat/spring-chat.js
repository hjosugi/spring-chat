// Connects each <deep-chat class="spring-chat"> to the server, over SSE or WebSocket.
// Both carry the same events: start, then text (with a delta) and ping, then done or error.

const INTERRUPTED = 'The connection was lost before the reply finished.';
const UNREACHABLE = 'The chat server could not be reached.';
const refused = (status) => `The chat server refused the message (HTTP ${status}).`;

// Spring Security's CSRF token: from the fragment when the template had it,
// else from the meta tags that Spring Security's reference has pages carry.
function csrfHeader(data) {
  const meta = (name) => document.querySelector(`meta[name="${name}"]`)?.content;
  const header = data.csrfHeader ?? meta('_csrf_header');
  const token = data.csrfToken ?? meta('_csrf');
  return header && token ? { [header]: token } : {};
}

// SSE: one POST per message; the response body is the event stream.
function sseTransport(data) {
  async function receive(message, reply, signal) {
    let response;
    try {
      response = await fetch(data.streamUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream', ...csrfHeader(data) },
        body: JSON.stringify(message),
        signal,
      });
    } catch {
      return reply.fail(UNREACHABLE);
    }
    if (!response.ok) return reply.fail(refused(response.status));

    try {
      const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
      let buffer = '';
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += value;
        const blocks = buffer.split(/\r?\n\r?\n/);
        buffer = blocks.pop();
        for (const block of blocks) {
          const lines = block.split(/\r?\n/).filter((line) => line.startsWith('data:'));
          if (lines.length) reply.event(JSON.parse(lines.map((line) => line.slice(5).trimStart()).join('\n')));
        }
      }
    } catch {
      // The stream broke; so did one that ended without done or error.
    }
    reply.fail(INTERRUPTED);
  }

  return (message, reply) => {
    const abort = new AbortController();
    receive(message, reply, abort.signal);
    return () => abort.abort();
  };
}

// WebSocket: one connection per chat, opened by the first message and reopened after a loss.
function socketTransport(data) {
  const url = new URL(data.socketUrl, location.href);
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  let socket = null; // the open connection
  let connecting = null; // or the promise of one
  let current = null; // the reply that the connection feeds

  function connect() {
    if (socket) return Promise.resolve(socket);
    connecting ??= new Promise((resolve, reject) => {
      const opening = new WebSocket(url);
      opening.onopen = () => {
        socket = opening;
        resolve(opening);
      };
      opening.onmessage = (frame) => current?.event(JSON.parse(frame.data));
      opening.onclose = () => {
        if (socket !== opening) return reject(new Error('never opened'));
        socket = null;
        current?.fail(INTERRUPTED);
      };
    }).finally(() => {
      connecting = null;
    });
    return connecting;
  }

  return (message, reply) => {
    current = reply;
    let stopped = false;
    connect()
      .then((open) => {
        if (!stopped) open.send(JSON.stringify({ type: 'message', ...message }));
      })
      .catch(() => reply.fail(UNREACHABLE));

    return () => {
      stopped = true;
      socket?.send(JSON.stringify({ type: 'stop' }));
    };
  };
}

// A conversation lasts as long as the page: a reload starts a new one.
function newConversationId() {
  // randomUUID exists on HTTPS and localhost only.
  if (crypto.randomUUID) return crypto.randomUUID();
  return Array.from(crypto.getRandomValues(new Uint8Array(16)), (byte) => byte.toString(16).padStart(2, '0')).join('');
}

function configure(chat) {
  const data = chat.dataset;
  const conversationId = newConversationId();
  const transport = data.transport === 'websocket' ? socketTransport(data) : sseTransport(data);

  chat.connect = {
    stream: true,
    handler: (body, signals) => {
      let started = false;
      let open = true;
      // deep-chat renders a text asynchronously, so what follows one waits for it:
      // otherwise the end of a reply could overtake its last words.
      let rendered = Promise.resolve();
      const afterRendering = (action) => {
        rendered = rendered.then(action).catch(() => {});
      };
      const reply = {
        event(event) {
          if (!open) return;
          // Every reply begins with start: what comes before it belongs to a reply that was stopped.
          if (event.type === 'start') started = true;
          else if (!started) return;
          else if (event.type === 'text') afterRendering(() => signals.onResponse({ text: event.delta }));
          else if (event.type === 'done') reply.close();
          else if (event.type === 'error') reply.fail(event.message);
        },
        fail(message) {
          if (!open) return;
          open = false;
          afterRendering(() => {
            // Not signals.onResponse({ error }): deep-chat throws on it when no text has arrived yet.
            signals.onClose();
            chat.addMessage({ error: message });
          });
        },
        close() {
          if (!open) return;
          open = false;
          afterRendering(() => signals.onClose());
        },
      };

      signals.onOpen();
      const stop = transport({ conversationId, text: body.messages.at(-1).text }, reply);
      signals.stopClicked.listener = () => {
        // Closed first, so that what stopping sets off cannot be taken for a failure.
        reply.close();
        stop();
      };
    },
  };

  const bubble = { maxWidth: '85%', padding: '0.5rem 0.875rem', borderRadius: '1rem', fontSize: '1rem', lineHeight: '1.6' };
  chat.chatStyle = {
    width: '100%',
    height: '100%',
    border: 'none',
    backgroundColor: 'transparent',
    fontFamily: 'var(--spring-chat-font)',
  };
  chat.messageStyles = {
    default: {
      shared: { bubble },
      user: { bubble: { backgroundColor: 'var(--spring-chat-primary)', color: 'var(--spring-chat-primary-foreground)' } },
      ai: { bubble: { backgroundColor: 'var(--spring-chat-muted)', color: 'var(--spring-chat-foreground)' } },
    },
    error: { bubble: { backgroundColor: 'transparent', color: 'var(--spring-chat-error)', fontSize: '0.875rem' } },
  };
  chat.textInput = {
    placeholder: { text: data.placeholder ?? '' },
    styles: {
      container: {
        width: 'calc(100% - 2rem)',
        borderRadius: '1.25rem',
        border: '1px solid var(--spring-chat-border)',
        backgroundColor: 'var(--spring-chat-background)',
        color: 'var(--spring-chat-foreground)',
        boxShadow: 'none',
      },
      text: { padding: '0.625rem 2.75rem 0.625rem 1rem', fontSize: '1rem' },
    },
  };
  // Errors are the server's own sentences, so they are shown as they are.
  chat.errorMessages = { displayServiceErrorMessages: true };
  if (data.intro) chat.introMessage = { text: data.intro };
}

document.querySelectorAll('deep-chat.spring-chat').forEach(configure);
