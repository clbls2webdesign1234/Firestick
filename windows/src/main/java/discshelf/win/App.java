package discshelf.win;

import com.formdev.flatlaf.FlatDarkLaf;
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Disc Shelf for Windows: plays DVD and Blu-ray ISO files and disc folders with the VLC engine. */
public final class App {
    private App() {}

    public static void main(String[] args) {
        FlatDarkLaf.setup();
        UIManager.put("Component.focusWidth", 2);
        UIManager.put("Button.arc", 12);
        UIManager.put("ScrollBar.width", 12);

        /* The packaged app ships the VLC engine in its "vlc" folder (set through
           jna.library.path). Without it, an installed 64-bit VLC is used instead. */
        boolean found;
        try {
            found = new NativeDiscovery().discover();
        } catch (Throwable t) {
            found = false;
        }
        if (!found) {
            JOptionPane.showMessageDialog(null,
                    "The VLC playback engine was not found.\n\n"
                            + "Keep the \"vlc\" folder next to DiscShelf.exe when you unzip the download,\n"
                            + "or install the 64-bit VLC media player from videolan.org.",
                    "Disc Shelf", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
        Library library = new Library();
        SwingUtilities.invokeLater(() -> new MainWindow(library).setVisible(true));
    }
}
