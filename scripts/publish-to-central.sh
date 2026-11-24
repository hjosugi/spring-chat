#!/usr/bin/env bash
#
# Publishes a tagged release of spring-chat to Maven Central.
#
#   /home/hsugi/ghq/github.com/hjosugi/spring-chat/scripts/publish-to-central.sh 0.1.0
#   /home/hsugi/ghq/github.com/hjosugi/spring-chat/scripts/publish-to-central.sh 0.1.0 --dry-run
#
# It builds from a clean export of the tag v<version>, so nothing in the working tree can get
# into the release. It stops, before it uploads anything, if something is missing: the tag, a
# GPG key that Central can find, the token, or if the version is on Central already.
#
# --dry-run does all of it except the signing and the upload, and needs no key and no token.
#
# The upload waits in the Central Portal. Look at it there, and press Publish: a version on
# Central cannot be taken back.
set -Eeuo pipefail

die() { echo "ERROR: $*" >&2; exit 1; }
say() { echo; echo "==> $*"; }

VERSION="${1:-}"
OPTION="${2:-}"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "Give the version of a release, such as 0.1.0, and not a SNAPSHOT."
DRY_RUN=false
if [[ "$OPTION" == "--dry-run" ]]; then
	DRY_RUN=true
elif [[ -n "$OPTION" ]]; then
	die "Unknown option: $OPTION"
fi
TAG="v$VERSION"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"

say "Checking what the release needs"
for tool in git curl tar java gpg; do
	command -v "$tool" >/dev/null || die "$tool is not installed."
done
JAVA_MAJOR="$(java -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')"
[[ "${JAVA_MAJOR:-0}" -ge 17 ]] || die "Java 17 or later is needed, and this is ${JAVA_MAJOR:-unknown}."
git -C "$REPO" rev-parse -q --verify "refs/tags/$TAG" >/dev/null || die "There is no tag $TAG in $REPO."
echo "tag $TAG in $REPO, Java $JAVA_MAJOR"

CENTRAL_POM="https://repo.maven.apache.org/maven2/io/github/hjosugi/spring-chat-spring-boot-starter/$VERSION/spring-chat-spring-boot-starter-$VERSION.pom"
HTTP="$(curl -s -o /dev/null -w '%{http_code}' "$CENTRAL_POM" || true)"
[[ "$HTTP" == "404" ]] || die "Expected 404 from Maven Central for $VERSION and got $HTTP: it is published already, or there is no network."
echo "$VERSION is not on Maven Central yet"

WORK="$(mktemp -d /var/tmp/spring-chat-release.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
git -C "$REPO" archive "$TAG" | tar -x -C "$WORK"
POM_VERSION="$(sed -n 's|^    <version>\(.*\)</version>$|\1|p' "$WORK/pom.xml" | head -1)"
[[ "$POM_VERSION" == "$VERSION" ]] || die "The pom of $TAG says $POM_VERSION, not $VERSION."
echo "exported $TAG to $WORK"

if ! $DRY_RUN; then
	say "Checking the GPG key"
	FINGERPRINT="${GPG_KEY_FINGERPRINT:-$(gpg --list-secret-keys --with-colons 2>/dev/null | awk -F: '$1=="fpr"{print $10; exit}')}"
	[[ -n "$FINGERPRINT" ]] || die "There is no GPG secret key. Make one with: gpg --full-generate-key"
	curl -fsS "https://keyserver.ubuntu.com/pks/lookup?op=get&options=mr&search=0x$FINGERPRINT" 2>/dev/null \
		| grep -q 'BEGIN PGP PUBLIC KEY BLOCK' \
		|| die "The public key $FINGERPRINT is not on keyserver.ubuntu.com, so Central cannot check the signatures. Run: gpg --keyserver keyserver.ubuntu.com --send-keys $FINGERPRINT   and wait a few minutes."
	[[ -t 0 && -t 1 ]] || die "Run it in a terminal: it asks for the key's passphrase and for the token."
	GPG_TTY="$(tty)"
	export GPG_TTY
	gpg-connect-agent updatestartuptty /bye >/dev/null 2>&1 || true
	echo "key $FINGERPRINT is on the keyserver. Signing a test asks for its passphrase now:"
	echo spring-chat | gpg --local-user "$FINGERPRINT" --clearsign >/dev/null || die "The key could not sign."

	say "The token of the Central Portal"
	if [[ -z "${CENTRAL_TOKEN_USERNAME:-}" ]]; then
		read -r -p "Token username: " CENTRAL_TOKEN_USERNAME </dev/tty
	fi
	if [[ -z "${CENTRAL_TOKEN_PASSWORD:-}" ]]; then
		read -r -s -p "Token password (not shown): " CENTRAL_TOKEN_PASSWORD </dev/tty
		echo
	fi
	[[ -n "$CENTRAL_TOKEN_USERNAME" && -n "$CENTRAL_TOKEN_PASSWORD" ]] || die "The token is empty."
	export CENTRAL_TOKEN_USERNAME CENTRAL_TOKEN_PASSWORD
	# Maven reads the token from the environment: no file holds it.
	cat > "$WORK/settings.xml" <<'XML'
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>${env.CENTRAL_TOKEN_USERNAME}</username>
      <password>${env.CENTRAL_TOKEN_PASSWORD}</password>
    </server>
  </servers>
</settings>
XML
fi

cd "$WORK"
if $DRY_RUN; then
	say "Dry run: tests, the jars of the sources and of the Javadoc, no signing, no upload"
	./mvnw -B -ntp -Prelease clean verify -Dgpg.skip=true
	say "Done. Nothing was signed or uploaded. To publish: $REPO/scripts/publish-to-central.sh $VERSION"
else
	say "Tests, jars, signatures, and the upload to the Central Portal"
	# -T 1: the modules build side by side by default (.mvn/maven.config), but the upload to
	# the Central Portal is one bundle, and it is built one module after the other.
	./mvnw -B -ntp -T 1 -s "$WORK/settings.xml" -Prelease clean deploy -Dgpg.keyname="$FINGERPRINT"
	say "Uploaded. Look at it, and press Publish: https://central.sonatype.com/publishing/deployments"
fi
