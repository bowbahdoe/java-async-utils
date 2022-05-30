import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

public final class Build {
    static final String VERSION = "0.0." + Instant.now().getEpochSecond();

    static void run(List<String> cmd) throws Exception {
        System.out.println("Running Command: " + String.join(" ", cmd));
        int exit = new ProcessBuilder(cmd)
            .inheritIO()
            .start()
            .waitFor();
        if (exit != 0) {
            System.exit(exit);
        }
    }

    static List<String> glob(String pattern) throws Exception {
        var matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        try (var f = Files.walk(Path.of("."))) {
            return f.filter(matcher::matches).map(Path::toString).toList();
        }
    }

    static void clean() throws Exception {
        run(List.of("rm", "-rf", "target"));
    }

    static void compile() throws Exception {
        var javacCmd = new ArrayList<>(List.of("javac", "-g",  "--release", "17", "-d", "target/classes"));
        javacCmd.addAll(glob("./src/*.java"));
        javacCmd.addAll(glob("./src/**/*.java"));
        run(javacCmd);
    }

    static void doc() throws Exception {
        var javadocCmd = new ArrayList<>(List.of("javadoc", "-d", "target/doc"));
        javadocCmd.addAll(glob("./src/*.java"));
        javadocCmd.addAll(glob("./src/**/*.java"));
        run(javadocCmd);
    }

    static void jar() throws Exception {
        compile();
        doc();
        run(List.of("mkdir", "target/deploy"));
        run(List.of("jar", "cf", "target/deploy/async-%s.jar".formatted(VERSION), "-C", "target/classes", "."));
        run(List.of("jar", "cf", "target/deploy/async-%s-javadoc.jar".formatted(VERSION), "-C", "target/doc", "."));
        run(List.of("jar", "cf", "target/deploy/async-%s-sources.jar".formatted(VERSION), "-C", "src", "."));
    }

    static void publish(String sonatypeUsername,
                        String sonatypePassword,
                        String gpgPassphrase) throws Exception {
        jar();
        Files.writeString(
            Path.of("target", "deploy", "async-%s.pom".formatted(VERSION)),
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd">
                <modelVersion>4.0.0</modelVersion>
                
                <groupId>dev.mccue</groupId>
                <artifactId>async</artifactId>
                <version>%s</version>
                <packaging>jar</packaging>
                
                <name>Async Utilities</name>
                <description>A few helpers for async code.</description>
                <url>https://github.com/bowbahdoe/java-async-utils</url>
                
                <licenses>
                  <license>
                    <name>The Apache Software License, Version 2.0</name>
                    <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
                  </license>
                </licenses>
                
                <developers>
                  <developer>
                    <name>Ethan McCue</name>
                    <email>ethan@mccue.dev</email>
                    <organization>McCue Software Solutions</organization>
                    <organizationUrl>https://www.mccue.dev</organizationUrl>
                  </developer>
                </developers>
                
                <scm>
                  <connection>scm:git:git://github.com/bowbahdoe/java-async-utils.git</connection>
                  <developerConnection>scm:git:ssh://github.com:bowbahdoe/java-async-utils.git</developerConnection>
                  <url>https://github.com/bowbahdoe/java-async-utils/tree/main</url>
                </scm>
            </project>
            """.formatted(VERSION)
        );

        var gpgCmd = new ArrayList<>(List.of("gpg", "-ab"));
        if (gpgPassphrase != null) {
            gpgCmd.addAll(List.of("--pinentry-mode", "loopback", "--passphrase", gpgPassphrase));
        }
        gpgCmd.addAll(List.of(
                "target/deploy/async-%s.pom".formatted(VERSION),
                "target/deploy/async-%s.jar".formatted(VERSION),
                "target/deploy/async-%s-javadoc.jar".formatted(VERSION),
                "target/deploy/async-%s-sources.jar".formatted(VERSION)

        ));
        run(gpgCmd);
        run(List.of("jar", "-cvf", "target/bundle.jar", "-C", "target/deploy", "."));

        var httpClient = HttpClient.newBuilder()
                .cookieHandler(new CookieManager())
                .build();


        var loginResponse = httpClient.send(
                HttpRequest.newBuilder()
                        .GET()
                        .uri(URI.create("https://s01.oss.sonatype.org/service/local/authentication/login"))
                        .header("Authorization", "Basic " + Base64.getEncoder()
                                .encodeToString(
                                        (sonatypeUsername + ":" + sonatypePassword).getBytes(StandardCharsets.UTF_8)
                                )
                        )
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        if (loginResponse.statusCode() / 10 != 20) {
            System.err.println("Invalid login");
            System.exit(1);
        }

        // {"repositoryUris":["https://s01.oss.sonatype.org/content/repositories/devmccue-1003"]}
        var boundary = "-------" + UUID.randomUUID();
        var uploadBody = """
                --%s
                Content-Disposition: form-data; name="file"; filename="bundle.jar"\r
                \r
                Content-Type: application/java-archive\r
                \r
                %s\r
                --%s--""".formatted(
                        boundary,
                Files.readString(Path.of("target/bundle.jar"), StandardCharsets.ISO_8859_1),
                boundary
        );
        var uploadResponse = httpClient.send(
                HttpRequest.newBuilder()
                        .POST(HttpRequest.BodyPublishers.ofString(uploadBody, StandardCharsets.ISO_8859_1))
                        .header("Content-Type", "multipart/form-data;boundary=%s".formatted(boundary))
                        .uri(URI.create("https://s01.oss.sonatype.org/service/local/staging/bundle_upload"))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        if (uploadResponse.statusCode() / 10 != 20) {
            System.err.println("Failed to upload");

            System.out.println(uploadResponse.body());
            System.exit(1);
        }

        var matcher = Pattern.compile("\"(https.*)\"")
                .matcher(uploadResponse.body());
        if (!matcher.find()) {
            System.err.println("Hacky json parsing fell apart");
            System.exit(1);
        }

        var url = matcher.group(1);
        System.out.println("Published to staging repository: " + url);
        System.out.println("Releasing staging repo after a few minute delay");
        Thread.sleep(1000 * 120);

        var stagingRepoId = Arrays.stream(url.split("/")).reduce((__, part) -> part)
                .orElseThrow();
        System.out.printf("Releasing %s!!\n", stagingRepoId);

        var releaseResponse = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("https://s01.oss.sonatype.org/service/local/staging/bulk/promote"))
                        .header("content-type", "application/json")
                        .header("accept", "application/json,application/vnd.siesta-error-v1+json,application/vnd.siesta-validation-errors-v1+json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                """
                                      {
                                        "data": {
                                            "autoDropAfterRelease": true,
                                            "description": "",
                                            "stagedRepositoryIds": ["%s"]
                                        }
                                      }
                                      """.formatted(stagingRepoId)
                        )).build(),
                HttpResponse.BodyHandlers.ofString()
        );

        if (releaseResponse.statusCode() / 10 != 20) {
            System.err.println("Failed to promote from staging to release");
            System.out.println(releaseResponse.body());
            System.exit(1);
        }
    }

    public static void main(String[] args) throws Exception {
        var options = "Options: clean, compile, doc, jar, publish";
        if (args.length == 0) {
            System.out.println(options);
            System.exit(1);
        }
        else {
            var cmd = args[0];
            switch (cmd) {
                case "clean" -> clean();
                case "compile" -> {
                    clean();
                    compile();
                }

                case "doc" -> {
                    clean();
                    doc();
                }
                case "jar" -> {
                    clean();
                    jar();
                }
                case "publish" -> {
                    if (args.length <= 1) {
                        var scanner = new Scanner(System.in);
                        System.out.print("Sonatype Username: ");
                        var username = scanner.next();
                        System.out.print("Sonatype Password: ");
                        var password = scanner.next();
                        System.out.print("GPG key: ");
                        var gpgSecret = scanner.next();
                        clean();
                        publish(username, password, gpgSecret);
                    }
                    else {
                        clean();
                        publish(args[1], args[2], args.length > 3 ? args[3] : null);
                    }
                }
                default -> {
                    System.out.println(options);
                    System.exit(1);
                }
            };
        }
    }
}
