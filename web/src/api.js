const BASE = '/api'

function getToken() {
  return localStorage.getItem('rag2agent_token')
}

async function request(path, options = {}) {
  const headers = { ...(options.headers || {}) }
  const t = getToken()
  if (t) headers.satoken = t
  if (!(options.body instanceof FormData)) {
    headers['Content-Type'] = 'application/json'
  }
  const resp = await fetch(BASE + path, { ...options, headers })
  const json = await resp.json()
  if (json.code !== '0') {
    throw new Error(json.message || '请求失败')
  }
  return json.data
}

export const auth = {
  register: (body) => request('/auth/register', { method: 'POST', body: JSON.stringify(body) }),
  login: (body) => request('/auth/login', { method: 'POST', body: JSON.stringify(body) }),
  me: () => request('/auth/me')
}

export const knowledgeBases = {
  create: (body, idempotencyKey = crypto.randomUUID()) => request('/knowledge-bases', {
    method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: JSON.stringify(body)
  }),
  list: () => request('/knowledge-bases')
}

export const documents = {
  upload: (file, kbId, idempotencyKey = crypto.randomUUID()) => {
    const fd = new FormData()
    fd.append('file', file)
    fd.append('kbId', String(kbId))
    return request('/documents/upload', { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: fd })
  },
  list: (kbId) => request('/documents?kbId=' + kbId),
  presign: (id) => request('/documents/' + id + '/presign')
}

export const agent = {
  chat: async (body, onEvent) => {
    const headers = { 'Content-Type': 'application/json' }
    const t = getToken()
    if (t) headers.satoken = t
    const resp = await fetch(BASE + '/chat', { method: 'POST', headers, body: JSON.stringify(body) })
    const contentType = resp.headers.get('content-type') || ''
    if (!resp.ok || !contentType.includes('text/event-stream')) {
      const json = await resp.json().catch(() => ({}))
      throw new Error(json.message || '对话请求失败')
    }
    await readSse(resp, onEvent)
  },
  replay: async (runId, lastEventId, onEvent) => {
    const headers = {}
    const t = getToken()
    if (t) headers.satoken = t
    if (lastEventId) headers['Last-Event-ID'] = lastEventId
    const resp = await fetch(BASE + '/agent/runs/' + runId + '/events', { headers })
    if (!resp.ok) throw new Error('断线恢复失败')
    await readSse(resp, onEvent)
  },
  getRun: (runId) => request('/agent/runs/' + runId),
  approve: (runId, approved) =>
    request('/agent/approvals/' + runId, { method: 'POST', body: JSON.stringify({ approved }) })
}

async function readSse(resp, onEvent) {
  const reader = resp.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let eventId = null
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split('\n')
    buffer = lines.pop() ?? ''
    for (const line of lines) {
      const trimmed = line.trim()
      if (trimmed.startsWith('id:')) eventId = trimmed.slice(3).trim()
      if (trimmed.startsWith('data:')) {
        const data = trimmed.slice(5).trim()
        if (data && data !== '[DONE]') {
          try {
            onEvent(JSON.parse(data), eventId)
            eventId = null
          } catch {
            // 忽略无法解析的分片
          }
        }
      }
    }
  }
}

export const evaluations = {
  importCases: (body) => request('/evaluations/cases/import', {
    method: 'POST',
    body: JSON.stringify(body)
  }),
  run: (body, idempotencyKey) => request('/evaluations/runs', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(body)
  }),
  matrix: (body, idempotencyKey) => request('/evaluations/matrix', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(body)
  }),
  listRuns: (kbId) => request('/evaluations/runs?kbId=' + kbId),
  getRun: (runId) => request('/evaluations/runs/' + runId),
  results: (runId) => request('/evaluations/runs/' + runId + '/results'),
  cancel: (runId) => request('/evaluations/runs/' + runId + '/cancel', { method: 'POST' })
}
