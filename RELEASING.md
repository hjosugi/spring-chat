# Releasing

A release goes to GitHub as a tag with notes, and to [Maven Central](https://central.sonatype.com) through the Central Portal. The build is ready for both; the account and the key are the maintainer's.

## Once

1. Sign in to the Central Portal and verify the namespace `io.github.hjosugi`. The portal asks for a temporary public repository on GitHub whose name it gives you.
2. Generate a user token in the portal, and put it in `~/.m2/settings.xml` under the server id `central`:

   ```xml
   <settings>
     <servers>
       <server>
         <id>central</id>
         <username>the token's name</username>
         <password>the token's password</password>
       </server>
     </servers>
   </settings>
   ```

3. Make a GPG key, and publish its public part to a keyserver such as `keys.openpgp.org`. Central checks the signatures against it.

## Each release

1. Set the version of every `pom.xml` to the release, without `-SNAPSHOT`, and commit it.
2. Run the tests, on Java 17 and on 21, and with `-Pvirtual-threads` on 21.
3. Tag it, `v` and the version, and push the commit and the tag.
4. Publish to Central:

   ```bash
   ./mvnw -Prelease deploy
   ```

   The profile builds the jars of the sources and of the Javadoc, signs every file with your key, and uploads one bundle of `spring-chat-parent`, `spring-chat-spring-boot-autoconfigure` and `spring-chat-spring-boot-starter`. The samples are left out. The bundle waits in the portal: look at it there, and press Publish. A version on Central cannot be taken back.
5. Make the release on GitHub from the tag, with its notes.
6. Set the next version, with `-SNAPSHOT`.

To see what the profile builds, without signing and without an upload:

```bash
./mvnw -Prelease verify -DskipTests -Dgpg.skip=true
```
