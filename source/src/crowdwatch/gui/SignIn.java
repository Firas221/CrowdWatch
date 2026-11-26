package crowdwatch.gui;

import java.awt.Component;
import java.awt.GridLayout;
import java.time.LocalDateTime;
import javax.swing.*;
import crowdwatch.net.Accounts;

/**
 * Sign-in for the Swing window, with the same users, passwords, two-step codes and lock as the web
 * console. When users exist, the window cannot be used without signing in; the signed-in name is
 * the officer name and the role decides which controls are available.
 */
public final class SignIn {
    private SignIn() { }

    /**
     * Asks for a user name, password and (when needed) the authenticator code until they are right.
     *
     * @param action what the check is for, written in the audit log ("desktop sign-in", "approval")
     * @return the user, or null if the person cancelled
     */
    public static Accounts.Who ask(Component parent, Accounts acc, String title, String action, java.util.function.Supplier<LocalDateTime> now) {
        JTextField user = new JTextField(16);
        JPasswordField pw = new JPasswordField(16);
        JTextField code = new JTextField(6);
        JLabel codeLabel = new JLabel("Code from the authenticator app");
        JLabel msg = new JLabel(" ");
        msg.setForeground(new java.awt.Color(0xB0, 0x00, 0x20));
        codeLabel.setVisible(false);
        code.setVisible(false);
        while (true) {
            JPanel p = new JPanel(new GridLayout(0, 1, 4, 4));
            p.add(new JLabel("User name")); p.add(user);
            p.add(new JLabel("Password")); p.add(pw);
            p.add(codeLabel); p.add(code);
            p.add(msg);
            int r = JOptionPane.showConfirmDialog(parent, p, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return null;
            Accounts.Who w = acc.verify(user.getText().trim(), new String(pw.getPassword()), code.isVisible() ? code.getText().trim() : null, now.get(), action);
            if (w != null) return w;
            Accounts.User u = acc.user(user.getText().trim());
            if (u != null && u.twoStep() && !code.isVisible()) { codeLabel.setVisible(true); code.setVisible(true); msg.setText("Enter the code from your authenticator app."); }
            else msg.setText("Wrong user name, password or code, or the account is locked.");
            pw.setText("");
        }
    }

    /**
     * Who approves entering a restricted area: the signed-in user if they are an OFFICER or ADMIN,
     * otherwise an officer types their own user name and password at this desk.
     *
     * @param me the signed-in user, or null when the window runs without users
     * @return the approver's name, or null if nobody approved
     */
    public static String approver(Component parent, Accounts acc, Accounts.Who me, java.util.function.Supplier<LocalDateTime> now) {
        if (acc == null || !acc.hasUsers() || me == null) return "supervisor";
        if (me.atLeast(Accounts.Role.OFFICER)) return me.name;
        Accounts.Who w = ask(parent, acc, "Officer approval for a restricted area", "approval", now);
        if (w == null) return null;
        if (!w.atLeast(Accounts.Role.OFFICER)) { JOptionPane.showMessageDialog(parent, w.name + " is not an officer: approval refused."); return null; }
        return w.name;
    }
}
