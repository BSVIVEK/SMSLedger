import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/** First-run helper: fetch the official wrapper, verify its pinned hash, then normal Gradle takes over. */
class GradleBootstrap {
    static final String HASH="2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046";
    public static void main(String[] args) throws Exception {
        Path destination=Path.of(args[0], "gradle", "wrapper", "gradle-wrapper.jar");
        Files.createDirectories(destination.getParent());
        if(Files.exists(destination)) return;
        System.out.println("Downloading official Gradle 8.11.1 wrapper (build tool only).");
        var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(30)).build();
        var request=HttpRequest.newBuilder(URI.create("https://raw.githubusercontent.com/gradle/gradle/v8.11.1/gradle/wrapper/gradle-wrapper.jar")).timeout(Duration.ofSeconds(60)).build();
        var response=client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if(response.statusCode()!=200) throw new java.io.IOException("Wrapper download HTTP " + response.statusCode());
        byte[] bytes=response.body();
        String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if(!actual.equals(HASH)) throw new SecurityException("Official wrapper checksum mismatch");
        Path temp=Files.createTempFile(destination.getParent(),"wrapper-", ".tmp");
        try { Files.write(temp,bytes); Files.move(temp,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(temp); }
    }
}
