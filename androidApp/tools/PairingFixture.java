import app.fanboxlibrary.shared.ImportSource;
import app.fanboxlibrary.shared.LibraryPost;
import app.fanboxlibrary.shared.LibraryStore;
import app.fanboxlibrary.shared.PostDraft;
import app.fanboxlibrary.transfer.PairHost;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import kotlin.Unit;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;

/** Debug fixture for PairedWindowsTest. It owns only a new, empty temporary library. */
public final class PairingFixture {
    public static void main(String[] args) throws Exception {
        Path temp = Path.of(args[0]);
        Files.createDirectory(temp);
        var store = new LibraryStore(temp.resolve("library").toFile());
        var draft = new PostDraft("fixture", "Windows fixture", 2025,
                "https://fixture.fanbox.cc/posts/880000001", null);
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("windows.txt"));
            zip.write("fixture-from-windows".repeat(10000).getBytes(StandardCharsets.UTF_8));
        }
        byte[] data = bytes.toByteArray();
        var imported = store.importManaged(draft,
                List.of(new ImportSource("windows.zip", () -> new ByteArrayInputStream(data))));
        if (imported.getAdded() != 1) throw new AssertionError(imported.getWarnings());
        LibraryPost published = store.posts().get(0);
        Path body = Path.of(published.getAssets().get(0).getPath());
        var cert = new HeldCertificate.Builder().commonName("loopback integration test").build();
        var tls = new HandshakeCertificates.Builder().heldCertificate(cert).build().sslContext();
        var downloads = new AtomicInteger();
        var completed = new CountDownLatch(1);
        try (var host = new PairHost(InetAddress.getByName("127.0.0.1"), tls, cert.certificate(),
                store, List.of(published), true, event -> {
                    try {
                        if (event.startsWith("폰으로 보냄")) {
                            if (downloads.incrementAndGet() == 1) Files.move(body, temp.resolve("withheld.bin"));
                            else completed.countDown();
                        }
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                    return Unit.INSTANCE;
                })) {
            Files.writeString(temp.resolve("pair.local"), host.getPairing().encode());
            if (!completed.await(180, TimeUnit.SECONDS)) throw new AssertionError("Phone did not finish");
            if (Files.exists(body)) throw new AssertionError("Source unexpectedly available during repeat pull");
            var received = store.posts().stream().filter(p -> p.getSourceUrl().endsWith("/880000002"))
                    .findFirst().orElseThrow();
            try (var zip = new ZipFile(new File(received.getAssets().get(0).getPath()))) {
                String text = new String(zip.getInputStream(zip.getEntry("phone.txt")).readAllBytes(), StandardCharsets.UTF_8);
                if (!text.equals("fixture-from-android")) throw new AssertionError("Push bytes mismatch");
            }
            System.out.println("Windows verified push and second pull with source bytes withheld");
        } finally {
            Files.deleteIfExists(temp.resolve("pair.local"));
        }
    }
}
