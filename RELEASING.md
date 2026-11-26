# Releasing

A release goes to GitHub as a tag with notes, and to [Maven Central](https://central.sonatype.com) through the Central Portal. The build is ready for both; the account and the key are the maintainer's.

## Once

1. The namespace `io.github.hjosugi` is verified in the Central Portal. For another namespace, register it there: the portal asks for a temporary public repository on GitHub whose name it gives you.
2. Generate a user token in the portal: the menu of your avatar, your account, Generate User Token. The portal shows the token's name and password once. Keep them out of files, in environment variables of your shell or of your password manager:

   ```bash
   export CENTRAL_TOKEN_USERNAME=...
   export CENTRAL_TOKEN_PASSWORD=...
   ```

   and let `~/.m2/settings.xml` read them, under the server id `central`:

   ```xml
   <settings>
     <servers>
       <server>
         <id>central</id>
         <username>${env.CENTRAL_TOKEN_USERNAME}</username>
         <password>${env.CENTRAL_TOKEN_PASSWORD}</password>
       </server>
     </servers>
   </settings>
   ```

3. Make a GPG key, and publish its public part to a keyserver. Central checks the signatures against it, and reads `keyserver.ubuntu.com`, `keys.openpgp.org` and `pgp.mit.edu`.

   ```bash
   gpg --full-generate-key                          # RSA 4096; the passphrase is asked for here, and never stored
   gpg --list-secret-keys --keyid-format long       # the key's id is on the line that starts with sec
   gpg --keyserver keyserver.ubuntu.com --send-keys <the key's id>
   ```

   The key carries the name and the e-mail address that you give it, and the keyserver publishes both. Use an address that may be public.

## From GitHub Actions

The workflow `.github/workflows/release.yml` publishes a tag to Central. Pushing to `main` does not publish: a version on Central cannot be taken back, and it cannot be published twice.

Once, put four secrets in the repository (Settings, Secrets and variables, Actions), or in an environment named `central`, which can also ask a reviewer to approve every run:

| Secret | What it is |
| --- | --- |
| `CENTRAL_TOKEN_USERNAME`, `CENTRAL_TOKEN_PASSWORD` | the user token of the Central Portal (see above) |
| `GPG_PRIVATE_KEY` | `gpg --armor --export-secret-keys <the key's id>` |
| `GPG_PASSPHRASE` | the passphrase of that key |

Secrets cannot be read back, so try them: Actions, Release, Run workflow, with a tag and the mode `check`. It looks for the secrets, signs with the key, looks for its public part on the keyserver, and signs the files of the build. Nothing is uploaded. The mode `dry-run` builds and tests with no signing and needs no secret.

Then a release is the steps below up to 3, and the tag starts the workflow. The upload waits in the Portal for you to press Publish.

## Each release

1. Set the version of every `pom.xml` to the release, without `-SNAPSHOT`, and commit it.
2. Run the tests, on Java 17 and on 21, and with `-Pvirtual-threads` on 21.
3. Tag it, `v` and the version, and push the commit and the tag. The tag starts the workflow above; skip the next step if you use it.
4. Or publish to Central from your machine, from the tag. The script exports the tag to a clean directory, checks that the key is on the keyserver and that the version is not on Central yet, asks for the key's passphrase and for the token, and runs `./mvnw -Prelease clean deploy`:

   ```bash
   /home/hsugi/ghq/github.com/hjosugi/spring-chat/scripts/publish-to-central.sh 0.1.0
   ```

   `--dry-run` after the version does everything but the signing and the upload, with no key and no token. Run it in a terminal. The profile builds the jars of the sources and of the Javadoc, signs every file with your key, and uploads one bundle of `spring-chat-parent`, `spring-chat-spring-boot-autoconfigure`, `spring-chat-spring-boot-starter` and `spring-chat-kotlin`. The samples are left out. The bundle waits in the portal: look at it there, and press Publish. A version on Central cannot be taken back.
5. Make the release on GitHub from the tag, with its notes.
6. Set the next version, with `-SNAPSHOT`.

To see what the profile builds, without signing and without an upload:

```bash
./mvnw -Prelease verify -DskipTests -Dgpg.skip=true
```
