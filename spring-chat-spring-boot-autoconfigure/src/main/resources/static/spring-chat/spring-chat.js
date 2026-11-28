// Connects each <deep-chat class="spring-chat"> to the server, over SSE or WebSocket.
// Both carry text, reasoning and tool progress between start and done, confirm or error.
// A confirm ends a reply that waits for the user to allow tool calls; the buttons it shows
// answer with a message that has decisions in place of a text.

// The tool calls are the model's own words: they are text, never markup.
function escapeHtml(text) {
  return String(text).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}

function confirmHtml(calls, labels) {
  const items = calls
    .map((call) => `<li><code>${escapeHtml(call.name)}</code> <code>${escapeHtml(call.arguments)}</code></li>`)
    .join('');
  return (
    `<div class="spring-chat-confirm"><p>${escapeHtml(labels.confirm)}</p><ul>${items}</ul>` +
    `<button class="spring-chat-allow">${escapeHtml(labels.allow)}</button> ` +
    `<button class="spring-chat-deny">${escapeHtml(labels.deny)}</button></div>`
  );
}

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
      return reply.fail(data.unreachable);
    }
    if (!response.ok) return reply.fail(data.refused.replaceAll('{status}', String(response.status)));

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
    reply.fail(data.interrupted);
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
        current?.fail(data.interrupted);
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
      .catch(() => reply.fail(data.unreachable));

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

function readAttachment(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve({
      name: file.name,
      mediaType: file.type || 'application/octet-stream',
      data: reader.result.slice(reader.result.lastIndexOf(',') + 1),
    });
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(file);
  });
}

async function userMessage(body, data) {
  if (!(body instanceof FormData)) return { text: body.messages.at(-1).text };
  // Deep Chat sends the current files separately. A file-only turn has no message1.
  const files = body.getAll('files');
  if (data.attachments !== 'true' || files.length > Number(data.maxFiles)
      || files.some((file) => !file.size || file.size > Number(data.maxFileSize))) {
    throw new Error(data.attachmentError);
  }
  return {
    text: JSON.parse(body.get('message1') ?? '{}').text ?? '',
    attachments: await Promise.all(files.map(readAttachment)),
  };
}

function configure(chat) {
  const data = chat.dataset;
  const conversationId = newConversationId();
  const transport = data.transport === 'websocket' ? socketTransport(data) : sseTransport(data);
  let pending = null; // the tool calls that the last reply waits for
  let decisions = null; // the answer to them that the buttons are sending

  // A button answers for every call of the reply: the model asked for them together.
  function decide(allow) {
    if (!pending) return;
    decisions = pending.map((call) => ({ id: call.id, allow }));
    pending = null;
    chat.submitUserMessage({ text: allow ? data.allow : data.deny });
  }

  if (data.attachments === 'true') {
    const files = { maxNumberOfFiles: Number(data.maxFiles), acceptedFormats: data.acceptedTypes };
    chat.mixedFiles = { files };
    const types = data.acceptedTypes.toLowerCase().split(',');
    const accepts = (type) => types.includes(type) || types.includes(type.split('/')[0] + '/*') || types.includes('*/*');
    const photo = ['image/png', 'image/jpeg'].find(accepts);
    if (photo) chat.camera = { files: { ...files, format: photo.split('/')[1] } };
    const audio = ['audio/webm', 'audio/mp4'].find((type) => globalThis.MediaRecorder?.isTypeSupported(type));
    if (audio && accepts(audio)) chat.microphone = { files: { ...files, format: audio.split('/')[1] } };
  }
  chat.requestBodyLimits = { maxMessages: 1 };
  // Deep Chat supplies toggle when the component initializes.
  chat.displayLoadingBubble = { toggle() {} };
  chat.connect = {
    stream: true,
    handler: async (body, signals) => {
      let started = false;
      let open = true;
      let answered = false; // whether the reply has any text
      let loading = true;
      let reasoning = '';
      // deep-chat renders a text asynchronously, so what follows one waits for it:
      // otherwise the end of a reply could overtake its last words.
      let rendered = Promise.resolve();
      const afterRendering = (action) => {
        rendered = rendered.then(action).catch(() => {});
      };
      const showProgress = (text) => afterRendering(() => {
        if (!open) return;
        loading = true;
        chat.displayLoadingBubble.toggle({ style: { html: `<span role="status">${escapeHtml(text)}</span>` } });
      });
      const hideProgress = () => {
        if (loading) chat.displayLoadingBubble.toggle();
        loading = false;
      };
      const reply = {
        event(event) {
          if (!open) return;
          // Every reply begins with start: what comes before it belongs to a reply that was stopped.
          if (event.type === 'start') started = true;
          else if (!started) return;
          else if (event.type === 'text') {
            answered = true;
            reasoning = '';
            afterRendering(() => {
              hideProgress();
              return signals.onResponse({ text: event.delta });
            });
          }
          else if (event.type === 'reasoning') {
            reasoning += event.delta;
            showProgress(reasoning);
          }
          else if (event.type === 'tool') {
            reasoning = '';
            showProgress(data.toolCall.replaceAll('{name}', () => event.name));
          }
          else if (event.type === 'done') reply.close(event.reason);
          else if (event.type === 'confirm') reply.confirm(event.calls);
          else if (event.type === 'error') reply.fail(event.message);
        },
        fail(message) {
          if (!open) return;
          open = false;
          afterRendering(() => {
            // Not signals.onResponse({ error }): deep-chat throws on it when no text has arrived yet.
            if (answered) hideProgress();
            signals.onClose();
            chat.addMessage({ error: message });
          });
        },
        close(reason) {
          if (!open) return;
          open = false;
          afterRendering(() => {
            if (answered && reason !== 'stopped') hideProgress();
            signals.onClose();
            const note = reason === 'truncated' || reason === 'filtered' ? data[reason]
              : (answered || reason === 'stopped' ? null : data.empty);
            if (note) chat.addMessage({ error: note });
          });
        },
        confirm(calls) {
          if (!open) return;
          open = false;
          afterRendering(() => {
            if (answered) hideProgress();
            signals.onClose();
            pending = calls;
            chat.addMessage({ html: confirmHtml(calls, data) });
          });
        },
      };

      signals.onOpen();
      chat.displayLoadingBubble.toggle({});
      // Typing another message instead of pressing a button leaves the calls unanswered.
      const answer = decisions;
      decisions = null;
      if (!answer) pending = null;
      let stop;
      signals.stopClicked.listener = () => {
        // Closed first, so that what stopping sets off cannot be taken for a failure.
        reply.close('stopped');
        stop?.();
      };
      try {
        const message = answer ? { decisions: answer } : await userMessage(body, data);
        if (open) stop = transport({ conversationId, ...message }, reply);
      } catch {
        reply.fail(data.attachmentError);
      }
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
  const button = { borderRadius: '0.5rem', border: '1px solid var(--spring-chat-border)', padding: '0.25rem 0.75rem', cursor: 'pointer', font: 'inherit' };
  chat.htmlClassUtilities = {
    'spring-chat-allow': {
      events: { click: () => decide(true) },
      styles: { default: { ...button, backgroundColor: 'var(--spring-chat-primary)', color: 'var(--spring-chat-primary-foreground)' } },
    },
    'spring-chat-deny': {
      events: { click: () => decide(false) },
      styles: { default: { ...button, backgroundColor: 'transparent', color: 'var(--spring-chat-foreground)' } },
    },
  };
  // Errors are the server's own sentences, so they are shown as they are.
  chat.errorMessages = { displayServiceErrorMessages: true };
  if (data.intro) chat.introMessage = { text: data.intro };
}

document.querySelectorAll('deep-chat.spring-chat').forEach(configure);
