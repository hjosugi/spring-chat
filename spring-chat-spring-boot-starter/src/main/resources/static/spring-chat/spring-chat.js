// Connects each <deep-chat class="spring-chat"> to the server, over SSE or WebSocket.
// Both carry the same events: start, text (with a delta), then done or error.

const INTERRUPTED = 'The connection was lost before the reply finished.';
const UNREACHABLE = 'The chat server could not be reached.';

// SSE: one POST per message; the response body is the event stream.
function sseTransport(data) {
  return (message, reply) => {
    const abort = new AbortController();
    const headers = { 'Content-Type': 'application/json', Accept: 'text/event-stream' };
    if (data.csrfHeader) headers[data.csrfHeader] = data.csrfToken;

    fetch(data.streamUrl, { method: 'POST', headers, body: JSON.stringify(message), signal: abort.signal })
      .then(async (response) => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
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
        reply.fail(INTERRUPTED);
      })
      .catch(() => {
        if (!abort.signal.aborted) reply.fail(UNREACHABLE);
      });

    return () => abort.abort();
  };
}

// WebSocket: one connection per chat, opened by the first message and reopened after a loss.
function socketTransport(data) {
  const url = new URL(data.socketUrl, location.href);
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  let socket = null;
  let current = null;

  function connect() {
    return new Promise((resolve, reject) => {
      if (socket?.readyState === WebSocket.OPEN) return resolve(socket);
      const opening = new WebSocket(url);
      opening.onopen = () => {
        socket = opening;
        resolve(opening);
      };
      opening.onmessage = (frame) => current?.event(JSON.parse(frame.data));
      opening.onclose = () => {
        // Closed before it opened: the promise rejects and the caller reports it.
        if (socket !== opening) return reject(new Error('closed'));
        socket = null;
        current?.fail(INTERRUPTED);
      };
    });
  }

  return (message, reply) => {
    current = reply;
    connect()
      .then((open) => open.send(JSON.stringify({ type: 'message', ...message })))
      .catch(() => reply.fail(UNREACHABLE));

    return () => {
      if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ type: 'stop' }));
    };
  };
}

function configure(chat) {
  const data = chat.dataset;
  // A conversation lasts as long as the page: a reload starts a new one.
  const conversationId = crypto.randomUUID();
  const transport = data.transport === 'websocket' ? socketTransport(data) : sseTransport(data);

  chat.connect = {
    stream: true,
    handler: (body, signals) => {
      let started = false;
      let open = true;
      const reply = {
        event(event) {
          if (!open) return;
          // Until its own start arrives, what comes in belongs to a reply that was stopped.
          if (event.type === 'start') started = true;
          else if (event.type === 'error') reply.fail(event.message);
          else if (!started) return;
          else if (event.type === 'text') signals.onResponse({ text: event.delta });
          else if (event.type === 'done') reply.close();
        },
        fail(message) {
          if (!open) return;
          open = false;
          // Not signals.onResponse({ error }): deep-chat throws on it when no text has arrived yet.
          signals.onClose();
          chat.addMessage({ error: message });
        },
        close() {
          if (!open) return;
          open = false;
          signals.onClose();
        },
      };

      signals.onOpen();
      const stop = transport({ conversationId, text: body.messages.at(-1).text }, reply);
      signals.stopClicked.listener = () => {
        stop();
        reply.close();
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
