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

## Each release

1. Set the version of every `pom.xml` to the release, without `-SNAPSHOT`, and commit it.
2. Run the tests, on Java 17 and on 21, and with `-Pvirtual-threads` on 21.
3. Tag it, `v` and the version, and push the commit and the tag.
4. Publish to Central, from the tag. The script exports the tag to a clean directory, checks that the key is on the keyserver and that the version is not on Central yet, asks for the key's passphrase and for the token, and runs `./mvnw -Prelease clean deploy`:

   ```bash
   /home/hsugi/ghq/github.com/hjosugi/spring-chat/scripts/publish-to-central.sh 0.1.0
   ```

   `--dry-run` after the version does everything but the signing and the upload, with no key and no token. Run it in a terminal. The profile builds the jars of the sources and of the Javadoc, signs every file with your key, and uploads one bundle of `spring-chat-parent`, `spring-chat-spring-boot-autoconfigure` and `spring-chat-spring-boot-starter`. The samples are left out. The bundle waits in the portal: look at it there, and press Publish. A version on Central cannot be taken back.
5. Make the release on GitHub from the tag, with its notes.
6. Set the next version, with `-SNAPSHOT`.

To see what the profile builds, without signing and without an upload:

```bash
./mvnw -Prelease verify -DskipTests -Dgpg.skip=true
```
