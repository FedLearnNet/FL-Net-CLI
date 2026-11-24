#!/bin/sh
# Creates or updates a user of a realm of the deployment in this DinD container, with the Keycloak admin CLI and the
# bootstrap admin:  kc-user.sh <realm> <username> <password> [role ...]
# Run it inside the container, e.g.  docker exec -i <container> sh -s -- FLNet-Platform admin secret Admin < kc-user.sh
set -eu

realm="$1"
username="$2"
password="$3"
shift 3
config=/tmp/kcadm-dind.config

keycloak="$(docker ps -q --filter label=com.docker.compose.service=keycloak | head -n 1)"
if [ -z "$keycloak" ]; then
  echo "No Keycloak runs in this container" >&2
  exit 1
fi

kcadm() {
  docker exec "$keycloak" /opt/keycloak/bin/kcadm.sh "$@" --config "$config"
}

docker exec "$keycloak" sh -c "/opt/keycloak/bin/kcadm.sh config credentials --config $config \
  --server http://localhost:8080/auth --realm master \
  --user \"\$KC_BOOTSTRAP_ADMIN_USERNAME\" --password \"\$KC_BOOTSTRAP_ADMIN_PASSWORD\""

if ! kcadm get users -r "$realm" -q "username=$username" -q exact=true --fields id | grep -q '"id"'; then
  kcadm create users -r "$realm" -s "username=$username" -s enabled=true -s emailVerified=true \
    -s firstName=FL-Net -s "lastName=$username" -s "email=$username@flnet.invalid"
fi
kcadm set-password -r "$realm" --username "$username" --new-password "$password"
for role in "$@"; do
  kcadm add-roles -r "$realm" --uusername "$username" --rolename "$role"
done
