// Thin fetch wrapper for the Swapp REST API.
// Authentication relies on the HttpOnly session cookie set by the server; no tokens are stored in the browser.

export class ApiError extends Error {
  constructor(status, message, fieldErrors) {
    super(message);
    this.status = status;
    this.fieldErrors = fieldErrors || {};
  }
}

const DEFAULT_MESSAGES = {
  0: 'Network error. Check your connection and try again.',
  400: 'Please check your input and try again.',
  401: 'Please sign in to continue.',
  403: 'You do not have permission to do that.',
  404: 'We could not find what you were looking for.',
  409: 'That conflicts with the current state. Please refresh and try again.',
  413: 'That file is too large.',
  429: 'Too many attempts. Please wait a moment.',
  500: 'Something went wrong on our side. Please try again.',
};

function buildUrl(path, query) {
  if (!query) return path;
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue;
    if (Array.isArray(value)) value.forEach((v) => params.append(key, v));
    else params.append(key, value);
  }
  const qs = params.toString();
  return qs ? `${path}?${qs}` : path;
}

export async function api(path, { method = 'GET', body, query, signal } = {}) {
  const options = { method, credentials: 'same-origin', headers: { Accept: 'application/json' }, signal };
  if (body instanceof FormData) {
    options.body = body;
  } else if (body !== undefined) {
    options.headers['Content-Type'] = 'application/json';
    options.body = JSON.stringify(body);
  }
  let response;
  try {
    response = await fetch(buildUrl(path, query), options);
  } catch (err) {
    if (err.name === 'AbortError') throw err;
    throw new ApiError(0, DEFAULT_MESSAGES[0]);
  }
  if (response.status === 204) return null;
  const text = await response.text();
  let data = null;
  if (text) {
    try { data = JSON.parse(text); } catch { data = null; }
  }
  if (!response.ok) {
    const fallback = DEFAULT_MESSAGES[response.status] || DEFAULT_MESSAGES[500];
    throw new ApiError(response.status, (data && data.message) || fallback, data && data.fieldErrors);
  }
  return data;
}

export const http = {
  get: (path, query, signal) => api(path, { query, signal }),
  post: (path, body) => api(path, { method: 'POST', body }),
  put: (path, body) => api(path, { method: 'PUT', body }),
  patch: (path, body) => api(path, { method: 'PATCH', body }),
  del: (path) => api(path, { method: 'DELETE' }),
};
