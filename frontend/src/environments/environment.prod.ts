export const environment = {
  production: true,
  auth0Domain: 'dev-ll8lyragj23p2c7l.us.auth0.com',
  auth0ClientId: '915VrzDeJpQbyjPHeOYfQONUFR0a5F08',
  auth0Audience: 'api.enpsecurechat.com',
  // Bypass Firebase Hosting CDN (which buffers SSE) — call Cloud Run directly
  streamApiBase: 'https://api.enpsecurechat.com',
};
