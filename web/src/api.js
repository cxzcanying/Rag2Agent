const BASE = '/api'

function getToken() {
  return localStorage.getItem('rag2agent_token')
}

async function request(path, options = {}) {
  const headers = {}
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
  create: (body) => request('/knowledge-bases', { method: 'POST', body: JSON.stringify(body) }),
  list: () => request('/knowledge-bases')
}

export const documents = {
  upload: (file, kbId) => {
    const fd = new FormData()
    fd.append('file', file)
    fd.append('kbId', String(kbId))
    return request('/documents/upload', { method: 'POST', body: fd })
  },
  list: (kbId) => request('/documents?kbId=' + kbId),
  presign: (id) => request('/documents/' + id + '/presign')
}
