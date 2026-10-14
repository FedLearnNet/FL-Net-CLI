"""Keycloak auth helpers.

Provides helpers to obtain an access token from a Keycloak server using
the Resource Owner Password Credentials grant (password grant) and to
build a headers dictionary suitable for passing to requests used by the
rest of the application.

Functions:
 - fetch_token(...) -> dict: returns the token response from Keycloak
 - get_auth_headers(...) -> dict: returns a headers dict including
   Authorization: Bearer <token> and a couple of sensible defaults.

This module deliberately keeps dependencies minimal and raises clear
exceptions for network / auth failures.
"""

from typing import Dict, Optional
import requests

class KeycloakAuthError(RuntimeError):
    """Raised when there's an error obtaining a token from Keycloak."""


def fetch_token(
    keycloak_server_url: str,
    realm: str,
    client_id: str,
    username: str,
    password: str,
    client_secret: Optional[str] = None,
    scope: Optional[str] = None,
    verify_ssl: bool = True,
) -> Dict:
    """Obtain a token response from Keycloak (password grant).

    Args:
        keycloak_server_url: base URL of Keycloak server, e.g.
            https://auth.example.com or https://example.com/auth
        realm: Keycloak realm name
        client_id: the client id to authenticate (public or confidential)
        username: resource owner's username
        password: resource owner's password
        client_secret: optional client secret for confidential clients
        verify_ssl: pass to requests.post verify parameter

    Returns:
        The parsed JSON response from the token endpoint.

    Raises:
        KeycloakAuthError on network / http / json errors.
    """

    base = keycloak_server_url.rstrip("/")
    token_url = f"{base}/realms/{realm}/protocol/openid-connect/token"

    data = {
        "grant_type": "password",
        "client_id": client_id,
        "username": username,
        "password": password,
    }
    # allow callers to request specific scopes (e.g. 'openid')
    if scope:
        data["scope"] = scope
    if client_secret:
        data["client_secret"] = client_secret

    headers = {"Content-Type": "application/x-www-form-urlencoded"}

    try:
        resp = requests.post(token_url, data=data, headers=headers, verify=verify_ssl, timeout=15)
    except requests.exceptions.RequestException as e:
        print("ERROR: The authentication failed, check the constants in dataGeneratorCLI.py!")
        raise KeycloakAuthError(f"Failed to connect to Keycloak token endpoint: {e}") from e

    if not resp.ok:
        # try to include a helpful message from Keycloak response body
        body = None
        try:
            body = resp.json()
        except ValueError:
            body = resp.text
        raise KeycloakAuthError(f"Token endpoint returned {resp.status_code}: {body}")

    try:
        return resp.json()
    except ValueError as e:
        raise KeycloakAuthError(f"Failed to decode JSON from Keycloak response: {e}") from e


def get_auth_headers(
    keycloak_server_url: str,
    realm: str,
    client_id: str,
    username: str,
    password: str,
    client_secret: Optional[str] = None,
    scope: Optional[str] = None,
    verify_ssl: bool = True,
) -> Dict[str, str]:
    """Return a headers dict ready to be used in HTTP requests.

    The returned dict always contains the Authorization header with a
    bearer access token and a few common headers seen in browser-originated
    API requests. Callers can update or extend the returned dict as needed.

    Example usage:
        headers = get_auth_headers(...)
        requests.get(url, headers=headers)
    """

    token_response = fetch_token(
        keycloak_server_url=keycloak_server_url,
        realm=realm,
        client_id=client_id,
        username=username,
        password=password,
        client_secret=client_secret,
    scope=scope,
        verify_ssl=verify_ssl,
    )

    access_token = token_response.get("access_token")
    if not access_token:
        raise KeycloakAuthError("Keycloak did not return an access_token in the token response")

    headers: Dict[str, str] = {
        "Authorization": f"Bearer {access_token}",
        # sensible defaults commonly required by browser-like clients
        "Accept": "application/json, text/plain, */*",
        "Accept-Language": "en-US,en;q=0.5",
        "User-Agent": "python-requests/featurecloud-auth",
        "Connection": "keep-alive",
        "DNT": "1",
        "Cache-Control": "no-cache",
        "Pragma": "no-cache",
    }

    return headers


__all__ = ["fetch_token", "get_auth_headers", "KeycloakAuthError"]
