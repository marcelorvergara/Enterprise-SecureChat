// Keycloak URL is the browser-accessible address (localhost in Docker Compose dev setup).
// In production, replace with your actual Keycloak hostname.
export const environment = {
  production: false,
  keycloakUrl: 'http://localhost:8080',
  keycloakRealm: 'enterprise-securechat',
  keycloakClientId: 'securechat-frontend',
};
