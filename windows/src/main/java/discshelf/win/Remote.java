package discshelf.win;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Component;
import java.awt.EventQueue;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Lets Disc Shelf be run without a keyboard or mouse.
 *
 * Media remotes: the play/pause, next, previous, stop and back buttons of a USB or Bluetooth
 * remote reach Windows as media keys, which Java never sees. A keyboard hook turns them into the
 * keys the app already understands, while Disc Shelf is the window in front.
 *
 * Phone remote: a small web page on this PC with the same buttons, for any phone or tablet on
 * the home network.
 */
final class Remote {
    private Remote() {}

    static final int PORT = 8642;

    private static HttpServer server;
    private static volatile boolean hooked;

    /** Windows virtual-key codes of remote buttons Java doesn't report, and the key each becomes. */
    private static final Map<Integer, Integer> MEDIA = new HashMap<>();

    static {
        MEDIA.put(0xB3, KeyEvent.VK_SPACE);      // play/pause
        MEDIA.put(0xFA, KeyEvent.VK_SPACE);      // play
        MEDIA.put(0xB0, KeyEvent.VK_PAGE_DOWN);  // next track: next chapter
        MEDIA.put(0xB1, KeyEvent.VK_PAGE_UP);    // previous track: previous chapter
        MEDIA.put(0xB2, KeyEvent.VK_ESCAPE);     // stop
        MEDIA.put(0xA6, KeyEvent.VK_ESCAPE);     // browser back, the Back button on most remotes
    }

    static void start() {
        try {
            startHook();
        } catch (Throwable ignored) {
        }
        try {
            startPhoneRemote();
        } catch (Throwable ignored) {
            /* No phone remote, but the app still runs. */
        }
    }

    // ------------------------------------------------------------ pressing keys

    /** Presses a key in whichever Disc Shelf window is in front, as if typed. */
    static void press(int keyCode) {
        EventQueue.invokeLater(() -> {
            Window w = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
            if (w == null) {
                for (Window any : Window.getWindows()) {
                    if (any.isShowing() && any.getOwner() == null) w = any;
                }
                if (w == null) return;
                w.toFront();
                w.requestFocus();
            }
            Component target = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            if (target == null) target = w.getMostRecentFocusOwner();
            if (target == null) target = w;
            long now = System.currentTimeMillis();
            EventQueue q = Toolkit.getDefaultToolkit().getSystemEventQueue();
            q.postEvent(new KeyEvent(target, KeyEvent.KEY_PRESSED, now, 0, keyCode, KeyEvent.CHAR_UNDEFINED));
            q.postEvent(new KeyEvent(target, KeyEvent.KEY_RELEASED, now, 0, keyCode, KeyEvent.CHAR_UNDEFINED));
        });
    }

    private static boolean ourWindowInFront() {
        WinDef.HWND fg = User32.INSTANCE.GetForegroundWindow();
        if (fg == null) return false;
        com.sun.jna.ptr.IntByReference pid = new com.sun.jna.ptr.IntByReference();
        User32.INSTANCE.GetWindowThreadProcessId(fg, pid);
        return pid.getValue() == Kernel32.INSTANCE.GetCurrentProcessId();
    }

    // ------------------------------------------------------------ media keys

    private static void startHook() {
        if (hooked || !System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        hooked = true;
        Thread t = new Thread(() -> {
            try {
                final WinUser.HHOOK[] hook = new WinUser.HHOOK[1];
                WinUser.LowLevelKeyboardProc proc = (nCode, wParam, info) -> {
                    if (nCode >= 0 && info != null) {
                        Integer key = MEDIA.get(info.vkCode);
                        if (key != null && ourWindowInFront()) {
                            if (wParam.intValue() == WinUser.WM_KEYDOWN || wParam.intValue() == WinUser.WM_SYSKEYDOWN) {
                                press(key);
                            }
                            return new WinDef.LRESULT(1);
                        }
                    }
                    return User32.INSTANCE.CallNextHookEx(hook[0], nCode, wParam,
                            new WinDef.LPARAM(com.sun.jna.Pointer.nativeValue(info.getPointer())));
                };
                hook[0] = User32.INSTANCE.SetWindowsHookEx(WinUser.WH_KEYBOARD_LL, proc,
                        Kernel32.INSTANCE.GetModuleHandle(null), 0);
                if (hook[0] == null) return;
                WinUser.MSG msg = new WinUser.MSG();
                while (User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
                    User32.INSTANCE.TranslateMessage(msg);
                    User32.INSTANCE.DispatchMessage(msg);
                }
            } catch (Throwable ignored) {
                /* Without the hook the remote's arrows, OK and Esc still work as ordinary keys. */
            }
        }, "remote-keys");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------ phone remote

    private static final Map<String, Integer> BUTTONS = new HashMap<>();

    static {
        BUTTONS.put("up", KeyEvent.VK_UP);
        BUTTONS.put("down", KeyEvent.VK_DOWN);
        BUTTONS.put("left", KeyEvent.VK_LEFT);
        BUTTONS.put("right", KeyEvent.VK_RIGHT);
        BUTTONS.put("ok", KeyEvent.VK_ENTER);
        BUTTONS.put("back", KeyEvent.VK_ESCAPE);
        BUTTONS.put("menu", KeyEvent.VK_CONTEXT_MENU);
        BUTTONS.put("play", KeyEvent.VK_SPACE);
        BUTTONS.put("next", KeyEvent.VK_PAGE_DOWN);
        BUTTONS.put("prev", KeyEvent.VK_PAGE_UP);
        BUTTONS.put("rew", KeyEvent.VK_J);
        BUTTONS.put("fwd", KeyEvent.VK_L);
        BUTTONS.put("discmenu", KeyEvent.VK_M);
    }

    private static void startPhoneRemote() {
        if (server != null || !Settings.phoneRemote()) return;
        try {
            server = HttpServer.create(new InetSocketAddress(PORT), 0);
        } catch (IOException e) {
            server = null;
            return;
        }
        server.createContext("/", Remote::handle);
        server.setExecutor(null);
        server.start();
    }

    private static void handle(HttpExchange x) throws IOException {
        String path = x.getRequestURI().getPath();
        byte[] body;
        String type;
        if (path.startsWith("/key/")) {
            Integer key = BUTTONS.get(path.substring(5));
            if (key != null) press(key);
            body = "ok".getBytes(StandardCharsets.UTF_8);
            type = "text/plain";
        } else {
            body = PAGE.getBytes(StandardCharsets.UTF_8);
            type = "text/html; charset=utf-8";
        }
        x.getResponseHeaders().set("Content-Type", type);
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.sendResponseHeaders(200, body.length);
        try (OutputStream o = x.getResponseBody()) {
            o.write(body);
        }
    }

    /** The address to open on a phone, such as http://192.168.1.20:8642, or null without a network. */
    static String address() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback() || n.isVirtual()) continue;
                String name = (n.getDisplayName() + " " + n.getName()).toLowerCase();
                if (name.contains("virtual") || name.contains("vmware") || name.contains("hyper-v")
                        || name.contains("vethernet")) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        return "http://" + a.getHostAddress() + ":" + PORT;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static final String PAGE = "<!doctype html><html><head><meta charset='utf-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1,user-scalable=no'>"
            + "<meta name='apple-mobile-web-app-capable' content='yes'>"
            + "<title>Disc Shelf remote</title><style>"
            + "html,body{margin:0;background:#111418;color:#eee;font-family:system-ui,sans-serif;"
            + "-webkit-user-select:none;user-select:none;touch-action:manipulation}"
            + "main{max-width:420px;margin:0 auto;padding:16px}"
            + "h1{font-size:18px;font-weight:600;margin:4px 0 16px;color:#9aa4b2}"
            + ".pad{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-bottom:18px}"
            + ".row{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-bottom:10px}"
            + "button{border:0;border-radius:16px;background:#232a33;color:#eee;font-size:20px;"
            + "padding:0;height:72px;-webkit-tap-highlight-color:transparent}"
            + "button:active{background:#4fa3ff;color:#000}"
            + ".ok{background:#2f6db3;font-weight:700}.e{visibility:hidden}"
            + "small{display:block;font-size:12px;color:#9aa4b2;margin-top:2px}"
            + "</style></head><body><main><h1>Disc Shelf remote</h1>"
            + "<div class='pad'>"
            + "<button class='e'></button><button data-k='up'>▲</button><button class='e'></button>"
            + "<button data-k='left'>◀</button><button class='ok' data-k='ok'>OK</button><button data-k='right'>▶</button>"
            + "<button class='e'></button><button data-k='down'>▼</button><button class='e'></button>"
            + "</div>"
            + "<div class='row'><button data-k='back'>Back</button><button data-k='menu'>☰<small>Options</small></button>"
            + "<button data-k='discmenu'>Disc<small>menu</small></button></div>"
            + "<div class='row'><button data-k='prev'>⏮<small>Chapter</small></button><button data-k='play'>⏯</button>"
            + "<button data-k='next'>⏭<small>Chapter</small></button></div>"
            + "<div class='row'><button data-k='rew'>−10 s</button><button class='e'></button>"
            + "<button data-k='fwd'>+30 s</button></div>"
            + "</main><script>"
            + "document.querySelectorAll('button[data-k]').forEach(function(b){"
            + "b.addEventListener('click',function(){"
            + "if(navigator.vibrate)navigator.vibrate(15);"
            + "fetch('/key/'+b.dataset.k,{method:'POST',cache:'no-store'}).catch(function(){});});});"
            + "</script></body></html>";
}
