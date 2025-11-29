package SpamDetector;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.concurrent.TimeUnit;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Improved Chat Server with Spam Filtering bridge to Python model.
 *
 * Features:
 *  - Swing UI with message bubbles + timestamp
 *  - Accepts multiple clients
 *  - Broadcasts messages to clients (non-blocking)
 *  - Spam detection via external Python script (predict.py)
 *  - Toggle spam filter ON/OFF
 *  - Block high-confidence spam (configurable threshold)
 *  - Save chat log
 */
public class Server {

    // ========== CONFIG ==========
    private static final String PYTHON_CMD = "python";// or "python3" on some systems
    private static final String PREDICT_SCRIPT = "predict.py";
    private static final double SPAM_CONF_THRESHOLD = 0.80; // block if confidence >= threshold
    private static final File CHAT_LOG = new File("chat_log.txt");
    private static final int MAX_MESSAGES = 500;

    // UI
    private final JFrame frame = new JFrame("Server Chat");
    private final JPanel messagesPanel = new JPanel();
    private final JScrollPane scrollPane;
    private final JTextField messageField = new JTextField();
    private final JButton sendButton = new JButton("Send");
    private final JButton toggleSpamBtn = new JButton("Spam Filter: ON");
    private final DefaultCaretEnforcer caretEnforcer;

    // Networking
    private final int port;
    private ServerSocket serverSocket;
    private final ExecutorService clientPool = Executors.newCachedThreadPool();
    private final List<DataOutputStream> outputs = new CopyOnWriteArrayList<>();

    // State
    private volatile boolean spamFilterEnabled = true;

    public Server(int port) {
        this.port = port;

        // build UI
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setSize(480, 780);
        frame.setLocation(200, 50);
        frame.setLayout(null);
        frame.getContentPane().setBackground(Color.WHITE);

        // Header
        JPanel header = new JPanel(null);
        header.setBackground(new Color(7, 94, 84));
        header.setBounds(0, 0, frame.getWidth(), 80);
        frame.add(header);

        // Back / close icon placeholder (just a button here)
        JButton closeBtn = new JButton("✕");
        closeBtn.setBounds(frame.getWidth() - 50, 20, 40, 30);
        closeBtn.setFocusable(false);
        closeBtn.setBackground(new Color(7,94,84));
        closeBtn.setForeground(Color.WHITE);
        closeBtn.setBorderPainted(false);
        closeBtn.addActionListener(e -> shutdown());
        header.add(closeBtn);

        // Profile image (load if available)
        JLabel profile = loadIconLabel("faraz2.jpg", 54, 54);
        if (profile == null) {
            profile = new JLabel();
            profile.setBounds(12, 12, 54, 54);
            profile.setOpaque(true);
            profile.setBackground(new Color(255,255,255));
            profile.setText("P"); profile.setHorizontalAlignment(SwingConstants.CENTER);
        } else {
            profile.setBounds(12, 12, 54, 54);
        }
        header.add(profile);

        JLabel name = new JLabel("Faraz");
        name.setForeground(Color.WHITE);
        name.setFont(new Font("SansSerif", Font.BOLD, 18));
        name.setBounds(80, 18, 200, 22);
        header.add(name);

        JLabel status = new JLabel("Online");
        status.setForeground(Color.WHITE);
        status.setFont(new Font("SansSerif", Font.PLAIN, 12));
        status.setBounds(80, 40, 200, 18);
        header.add(status);

        // spam toggle near top-right
        toggleSpamBtn.setBounds(frame.getWidth() - 160, 20, 100, 30);
        toggleSpamBtn.setFocusable(false);
        toggleSpamBtn.addActionListener(e -> toggleSpamFilter());
        header.add(toggleSpamBtn);

        // messagesPanel setup
        messagesPanel.setLayout(new BoxLayout(messagesPanel, BoxLayout.Y_AXIS));
        messagesPanel.setBackground(Color.WHITE);

        JPanel holder = new JPanel(new BorderLayout());
        holder.setBackground(Color.WHITE);
        holder.add(messagesPanel, BorderLayout.NORTH);

        scrollPane = new JScrollPane(holder);
        scrollPane.setBounds(10, 90, frame.getWidth() - 30, 560);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        frame.add(scrollPane);

        // input area
        messageField.setBounds(10, 660, frame.getWidth() - 140, 46);
        messageField.setFont(new Font("SansSerif", Font.PLAIN, 14));
        frame.add(messageField);

        sendButton.setBounds(frame.getWidth() - 115, 660, 105, 46);
        sendButton.setBackground(new Color(7, 94, 84));
        sendButton.setForeground(Color.WHITE);
        sendButton.setBorderPainted(false);
        frame.add(sendButton);

        caretEnforcer = new DefaultCaretEnforcer(scrollPane);

        // actions
        sendButton.addActionListener(e -> sendLocalMessage());
        messageField.addActionListener(e -> sendLocalMessage());

        // window close
        frame.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });

        frame.setVisible(true);

        // start server
        startServer();
    }

    // ---------------- UI / message helpers ----------------

    private void sendLocalMessage() {
        String text = messageField.getText();
        if (text == null || text.trim().isEmpty()) return;

        SpamFilter.Result res = SpamFilter.classifyIfEnabled(text, spamFilterEnabled);
        
        String classification = (res != null) ? 
            String.format("%s (%.2f)", res.label, res.confidence) : null;
            
        boolean isSpamAndBlock = (res != null && "spam".equals(res.label) && res.confidence >= SPAM_CONF_THRESHOLD);
        
        if (isSpamAndBlock) {
            appendMessage(formatMessagePanel("[BLOCKED SPAM - Outgoing] " + text, true, true, classification));
            writeLog("BLOCKED_OUTGOING", text, res.confidence);
            // Notify connected clients that the server attempted to send a blocked message
            String notif = "[BLOCKED SPAM - Outgoing from Server]";
            broadcast(notif, null);
            messageField.setText("");
            return;
        }

        appendMessage(formatMessagePanel(text, true, false, classification));
        // broadcast server's outgoing message to all clients
        broadcast(text, null);
        writeLog("SERVER", text, (res == null ? -1.0 : res.confidence));
        messageField.setText("");
    }

    private void appendMessage(JPanel panel) {
        SwingUtilities.invokeLater(() -> {
            // cap oldest messages
            if (messagesPanel.getComponentCount() / 2 > MAX_MESSAGES) {
                // remove earliest two entries (panel + spacer)
                messagesPanel.remove(0);
                if (messagesPanel.getComponentCount() > 0) messagesPanel.remove(0);
            }
            messagesPanel.add(panel);
            messagesPanel.add(Box.createVerticalStrut(8));
            messagesPanel.revalidate();
            messagesPanel.repaint();
            caretEnforcer.scrollToBottom();
        });
    }

    /**
     * Create a message bubble panel.
     *
     * @param text        message text
     * @param sentByServer true -> right aligned (server), false -> left aligned (client)
     * @param blocked     true -> render as blocked spam (red)
     * @param classification Optional result from spam classification (label|confidence)
     */
    private JPanel formatMessagePanel(String text, boolean sentByServer, boolean blocked, String classification) {
        // 1. Message Content
        JLabel label = new JLabel("<html>" + escapeHtml(text).replaceAll("\n", "<br>") + "</html>");
        label.setFont(new Font("Tahoma", Font.PLAIN, 15));
        label.setOpaque(true);
        label.setBorder(new EmptyBorder(10, 12, 10, 12));
        label.setMaximumSize(new Dimension(320, Integer.MAX_VALUE));
        
        Color bubbleColor, textColor;
        if (blocked) {
            bubbleColor = new Color(220, 40, 40); // red for blocked
            textColor = Color.WHITE;
        } else {
            bubbleColor = sentByServer ? new Color(37, 211, 102) : new Color(236, 229, 221);
            textColor = Color.BLACK;
        }
        label.setBackground(bubbleColor);
        label.setForeground(textColor);

        // 2. Time Stamp
        Calendar cal = Calendar.getInstance();
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm");
        JLabel time = new JLabel(sdf.format(cal.getTime()));
        time.setFont(new Font("Tahoma", Font.PLAIN, 10));
        time.setForeground(Color.GRAY);

        // 3. Optional Spam/Ham Classification Info (NEW)
        JLabel classificationLabel = null;
        if (classification != null) {
            classificationLabel = new JLabel(classification);
            classificationLabel.setFont(new Font("Tahoma", Font.ITALIC, 10));
            classificationLabel.setForeground(blocked ? Color.LIGHT_GRAY : Color.DARK_GRAY);
            classificationLabel.setAlignmentX(Component.RIGHT_ALIGNMENT);
            classificationLabel.setBorder(new EmptyBorder(0, 12, 0, 12)); // Match padding
        }
        
        // 4. Inner Panel (Message + Classification Stacked)
        JPanel bubbleContent = new JPanel();
        bubbleContent.setLayout(new BoxLayout(bubbleContent, BoxLayout.Y_AXIS));
        bubbleContent.setBackground(bubbleColor);
        
        label.setAlignmentX(Component.RIGHT_ALIGNMENT);
        
        // Add message label
        bubbleContent.add(label);
        
        // Add classification info (only if blocked or for logging/transparency)
        if (classificationLabel != null) {
             bubbleContent.add(Box.createVerticalStrut(2)); 
             bubbleContent.add(classificationLabel);
        }
        
        bubbleContent.setMaximumSize(bubbleContent.getPreferredSize());


        // 5. Outer Panel (Positioning the Bubble Left/Right)
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(Color.WHITE);
        
        JPanel timePanel = new JPanel(new BorderLayout());
        timePanel.setBackground(Color.WHITE);
        
        // Use the combined bubbleContent panel in the row
        if (sentByServer) {
            panel.add(bubbleContent, BorderLayout.LINE_END); // Server's message: right side
            timePanel.add(time, BorderLayout.LINE_END);
        } else {
            panel.add(bubbleContent, BorderLayout.LINE_START); // Client's message: left side
            timePanel.add(time, BorderLayout.LINE_START);
        }
        
        // 6. Overall Vertical Stack (Message Row + Time Row)
        JPanel outerStack = new JPanel();
        outerStack.setLayout(new BoxLayout(outerStack, BoxLayout.Y_AXIS));
        outerStack.setBackground(Color.WHITE);
        outerStack.add(panel);
        outerStack.add(timePanel);
        
        return outerStack;
    }

    // ------------------- Networking / Server -------------------

    /**
     * Broadcast a message to all connected clients.
     * If `exclude` is non-null the matching DataOutputStream will NOT receive the message
     * (used to avoid echoing a client's own message back to them).
     */
    private void broadcast(String message, DataOutputStream exclude) {
        for (DataOutputStream dout : outputs) {
            if (exclude != null && dout == exclude) continue;
            clientPool.submit(() -> {
                try {
                    synchronized (dout) {
                        dout.writeUTF(message);
                        dout.flush();
                    }
                } catch (IOException e) {
                    // remove dead stream
                    outputs.remove(dout);
                    try { dout.close(); } catch (IOException ignored) {}
                }
            });
        }
    }

    private void startServer() {
        Thread t = new Thread(() -> {
            try (ServerSocket ss = new ServerSocket(port)) {
                serverSocket = ss;
                // FIX: Added 'null' for classification argument
                appendMessage(formatMessagePanel("Server listening on port " + port, true, false, null)); 
                while (!ss.isClosed()) {
                    Socket client = ss.accept();
                    clientPool.submit(new ClientHandler(client));
                }
            } catch (IOException e) {
                // FIX: Added 'null' for classification argument
                appendMessage(formatMessagePanel("Server stopped: " + e.getMessage(), true, false, null)); 
            }
        }, "Server-Accept-Thread");
        t.setDaemon(true);
        t.start();
    }

    private class ClientHandler implements Runnable {
        private final Socket socket;
        private DataInputStream din;
        private DataOutputStream dout;

        ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            String remoteAddr = socket.getRemoteSocketAddress().toString();
            try {
                din = new DataInputStream(socket.getInputStream());
                dout = new DataOutputStream(socket.getOutputStream());
                outputs.add(dout);

                // FIX: Added 'null' for classification argument
                appendMessage(formatMessagePanel("Client connected: " + remoteAddr, false, false, null)); 
                writeLog("CONNECT", remoteAddr, -1.0);

                while (!socket.isClosed()) {
                    String msg;
                    try {
                        msg = din.readUTF();
                    } catch (EOFException | SocketException se) {
                        break;
                    }
                    if (msg == null) break;

                    // classify incoming message
                    SpamFilter.Result res = SpamFilter.classifyIfEnabled(msg, spamFilterEnabled);
                    String classification = (res != null) ? 
                        String.format("%s (%.2f)", res.label, res.confidence) : null;
                    
                    boolean isSpamAndBlock = (res != null && "spam".equals(res.label) && res.confidence >= SPAM_CONF_THRESHOLD);

                    if (isSpamAndBlock) {
                        // blocked: show in UI as blocked (left, red) and do NOT broadcast
                        appendMessage(formatMessagePanel("[BLOCKED SPAM] " + msg, false, true, classification));
                        writeLog("BLOCKED_INCOMING", msg, res.confidence);
                        continue;
                    }

                    // normal message: show and broadcast (do not echo back to sender)
                    appendMessage(formatMessagePanel(msg, false, false, classification));
                    broadcast(msg, dout);
                    writeLog("CLIENT", msg, (res == null ? -1.0 : res.confidence));
                }
            } catch (IOException e) {
                // FIX: Added 'null' for classification argument
                appendMessage(formatMessagePanel("Client error: " + remoteAddr + " (" + e.getMessage() + ")", false, false, null)); 
            } finally {
                if (dout != null) {
                    outputs.remove(dout);
                    try { dout.close(); } catch (IOException ignored) {}
                }
                try { if (!socket.isClosed()) socket.close(); } catch (IOException ignored) {}
                // FIX: Added 'null' for classification argument
                appendMessage(formatMessagePanel("Client disconnected: " + remoteAddr, false, false, null)); 
                writeLog("DISCONNECT", remoteAddr, -1.0);
            }
        }
    }

    // ----------------- Spam Filter Bridge -----------------

    /**
     * SpamFilter runs the external Python script and parses label|confidence output.
     * If the filter is disabled, classifyIfEnabled returns null quickly (no blocking).
     */
    public static class SpamFilter {
        public static class Result {
            public final String label;
            public final double confidence;
            public Result(String label, double confidence) {
                this.label = label;
                this.confidence = confidence;
            }
        }

        /**
         * If enabled==false -> returns null (no classification performed).
         * If enabled==true -> calls external script and returns a Result (never null unless error).
         */
        public static Result classifyIfEnabled(String message, boolean enabled) {
            if (!enabled) return null;
            return classify(message);
        }

        private static Result classify(String message) {
            // sanitize message argument for command-line (we'll pass as single argument)
            try {
                ProcessBuilder pb = new ProcessBuilder(PYTHON_CMD, PREDICT_SCRIPT, message);
                pb.redirectErrorStream(true);
                Process p = pb.start();

                // read one line of output
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line = r.readLine();
                // wait a short while but don't block too long
                try { if (!p.waitFor(3, TimeUnit.SECONDS)) { p.destroyForcibly(); } } catch (InterruptedException ignored) {}

                if (line != null) {
                    // expected format: label|confidence  e.g. spam|0.9943
                    String[] parts = line.trim().split("\\|");
                    if (parts.length >= 2) {
                        String label = parts[0].trim();
                        double conf = 0.0;
                        try { conf = Double.parseDouble(parts[1]); } catch (NumberFormatException ignore) {}
                        return new Result(label, conf);
                    } else {
                        // if output just label, assume confidence 1.0
                        return new Result(line.trim(), 1.0);
                    }
                }
            } catch (IOException e) {
                // log to console only — don't throw to UI thread
                System.err.println("SpamFilter error: " + e.getMessage());
            }
            return new Result("error", 0.0);
        }
    }

    // ----------------- Utilities -----------------

    private void toggleSpamFilter() {
        spamFilterEnabled = !spamFilterEnabled;
        toggleSpamBtn.setText("Spam Filter: " + (spamFilterEnabled ? "ON" : "OFF"));
        // FIX: Added 'null' for classification argument
        appendMessage(formatMessagePanel("Spam filter turned " + (spamFilterEnabled ? "ON" : "OFF"), true, false, null)); 
    }

    private void shutdown() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
        } catch (IOException ignored) {}
        for (DataOutputStream dout : outputs) {
            try { dout.close(); } catch (IOException ignored) {}
        }
        outputs.clear();
        clientPool.shutdownNow();
        frame.dispose();
        System.exit(0);
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private JLabel loadIconLabel(String resourceName, int w, int h) {
        try {
            // Try classpath resource first
            java.net.URL url = ClassLoader.getSystemResource(resourceName);
            BufferedImage img = null;
            if (url != null) {
                img = ImageIO.read(url);
            } else {
                // Fall back to filesystem (project folder)
                File f = new File(resourceName);
                if (f.exists()) {
                    img = ImageIO.read(f);
                }
            }
            if (img == null) return null;
            Image scaled = img.getScaledInstance(w, h, Image.SCALE_SMOOTH);
            return new JLabel(new ImageIcon(scaled));
        } catch (Throwable t) {
            return null;
        }
    }

    // Ensure scrollbar stays at bottom when new messages appended
    private static class DefaultCaretEnforcer {
        private final JScrollPane scrollPane;
        DefaultCaretEnforcer(JScrollPane sp) { this.scrollPane = sp; }
        void scrollToBottom() {
            SwingUtilities.invokeLater(() -> {
                JScrollBar bar = scrollPane.getVerticalScrollBar();
                bar.setValue(bar.getMaximum());
            });
        }
    }

    // Simple chat log writer (append)
    private synchronized void writeLog(String tag, String text, double confidence) {
        try (FileWriter fw = new FileWriter(CHAT_LOG, true);
             BufferedWriter bw = new BufferedWriter(fw);
             PrintWriter out = new PrintWriter(bw)) {
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Calendar.getInstance().getTime());
            out.printf("[%s] %s | conf=%.4f | %s%n", ts, tag, confidence, text);
        } catch (IOException ignored) {}
    }

    // ----------------- Main -----------------

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Server(6001));
    }
}