// Keycloak URL is the browser-accessible address (localhost in Docker Compose dev setup).
// In production, replace with your actual Keycloak hostname.
export const environment = {
  production: true,
  keycloakUrl: 'https://enpsecurechat.com',
  keycloakRealm: 'enterprise-securechat',
  keycloakClientId: 'securechat-frontend',
  apiUrl: '/api' 
};
