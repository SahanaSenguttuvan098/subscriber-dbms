import java.io.Console;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.Arrays;
import java.util.Scanner;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class SubscriberApp {
    private static final int PAGE_SIZE = 10;
    private static final int PASSWORD_ITERATIONS = 210000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String[] STATUSES = {"ACTIVE", "PAUSED", "CANCELLED"};
    private static final String DB_URL = setting("DB_URL", "jdbc:mysql://localhost:3306/customer_db");
    private static final String DB_USER = setting("DB_USER", "root");
    private static final String DB_PASSWORD = setting("DB_PASSWORD", setting("MYSQL_PASSWORD", ""));

    private final Scanner scanner = new Scanner(System.in);
    private final Console console = System.console();
    private int currentUserId;
    private String currentUsername;

    public static void main(String[] args) {
        SubscriberApp app = new SubscriberApp();
        if (DB_PASSWORD.isEmpty()) {
            System.err.println("Database password is missing. Set DB_PASSWORD (or MYSQL_PASSWORD) and run again.");
            return;
        }

        try (Connection connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            app.run(connection);
        } catch (SQLException e) {
            System.err.println("Could not connect to or use the database: " + e.getMessage());
            System.err.println("Check MySQL is running, the credentials are correct, and schema.sql or migration.sql was applied.");
        } catch (GeneralSecurityException e) {
            System.err.println("Secure password handling is unavailable: " + e.getMessage());
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
        } finally {
            app.scanner.close();
        }
    }

    private void run(Connection connection) throws SQLException, GeneralSecurityException {
        printBanner();
        if (countUsers(connection) == 0) {
            createFirstAdmin(connection);
        }
        if (!login(connection)) {
            System.out.println("Too many failed sign-in attempts. Exiting.");
            return;
        }
        showMenu(connection);
    }

    private void printBanner() {
        System.out.println("\n============================================================");
        System.out.println("             SUBSCRIBER MANAGEMENT SYSTEM");
        System.out.println("============================================================");
    }

    private int countUsers(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM users")) {
            result.next();
            return result.getInt(1);
        }
    }

    private void createFirstAdmin(Connection connection) throws SQLException, GeneralSecurityException {
        System.out.println("\nNo application account exists. Create the first administrator.");
        String username;
        do {
            username = prompt("Administrator username").trim();
            if (username.length() < 3 || username.length() > 50) {
                System.out.println("Username must be between 3 and 50 characters.");
                username = "";
            }
        } while (username.isEmpty());

        String password = readNewPassword("Administrator password: ");

        String query = "INSERT INTO users (username, password_hash) VALUES (?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, username);
            statement.setString(2, hashPassword(password));
            statement.executeUpdate();
        }
        System.out.println("Administrator account created. Sign in to continue.");
    }

    private boolean login(Connection connection) throws SQLException, GeneralSecurityException {
        for (int attempt = 1; attempt <= 5; attempt++) {
            String username = prompt("\nUsername: ").trim();
            String password = readPassword("Password: ");
            String query = "SELECT id, username, password_hash, legacy_password FROM users WHERE username = ?";
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setString(1, username);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next() && verifyPassword(connection, result.getInt("id"), password,
                            result.getString("password_hash"), result.getString("legacy_password"))) {
                        currentUserId = result.getInt("id");
                        currentUsername = result.getString("username");
                        try (PreparedStatement update = connection.prepareStatement(
                                "UPDATE users SET last_login_at = CURRENT_TIMESTAMP WHERE id = ?")) {
                            update.setInt(1, currentUserId);
                            update.executeUpdate();
                        }
                        System.out.println("\nSigned in as " + currentUsername + ".");
                        return true;
                    }
                }
            }
            System.out.println("Invalid username or password (" + attempt + "/5).");
        }
        return false;
    }

    private boolean verifyPassword(Connection connection, int userId, String password,
                                   String encodedHash, String legacyPassword)
            throws SQLException, GeneralSecurityException {
        if (encodedHash != null && !encodedHash.isEmpty()) {
            String[] parts = encodedHash.split("\\$", -1);
            if (parts.length != 4 || !"PBKDF2".equals(parts[0])) {
                throw new SQLException("Unsupported password hash format. Reset this user's password.");
            }
            try {
                int iterations = Integer.parseInt(parts[1]);
                byte[] salt = Base64.getDecoder().decode(parts[2]);
                byte[] expected = Base64.getDecoder().decode(parts[3]);
                if (iterations < 100000 || iterations > 1000000 || salt.length != 16 || expected.length != 32) {
                    throw new IllegalArgumentException("Invalid password hash parameters.");
                }
                char[] passwordChars = password.toCharArray();
                byte[] actual;
                try {
                    actual = deriveKey(passwordChars, salt, iterations);
                } finally {
                    Arrays.fill(passwordChars, '\0');
                }
                return MessageDigest.isEqual(expected, actual);
            } catch (IllegalArgumentException e) {
                throw new SQLException("Corrupt password hash. Reset this user's password.", e);
            }
        }
        if (legacyPassword == null || !MessageDigest.isEqual(
                password.getBytes(StandardCharsets.UTF_8), legacyPassword.getBytes(StandardCharsets.UTF_8))) {
            return false;
        }

        String upgradedPassword = password;
        if (password.length() < 12) {
            System.out.println("Your legacy password is too short. Set a new password of at least 12 characters.");
            upgradedPassword = readNewPassword("New password: ");
        }
        try (PreparedStatement upgrade = connection.prepareStatement(
                "UPDATE users SET password_hash = ?, legacy_password = NULL WHERE id = ?")) {
            upgrade.setString(1, hashPassword(upgradedPassword));
            upgrade.setInt(2, userId);
            upgrade.executeUpdate();
        }
        return true;
    }

    private void showMenu(Connection connection) throws SQLException, GeneralSecurityException {
        while (true) {
            System.out.println("\n---------------------- MAIN MENU ----------------------");
            System.out.println("1. Dashboard");
            System.out.println("2. List subscribers");
            System.out.println("3. Add subscriber");
            System.out.println("4. Search subscribers");
            System.out.println("5. Update subscriber");
            System.out.println("6. Change subscriber status");
            System.out.println("7. Delete subscriber");
            System.out.println("8. Export subscribers to CSV");
            System.out.println("9. Recent activity");
            System.out.println("0. Exit");
            String choice = prompt("Choose an option: ").trim();
            try {
                switch (choice) {
                    case "1": dashboard(connection); break;
                    case "2": listSubscribers(connection); break;
                    case "3": addSubscriber(connection); break;
                    case "4": searchSubscribers(connection); break;
                    case "5": updateSubscriber(connection); break;
                    case "6": changeStatus(connection); break;
                    case "7": deleteSubscriber(connection); break;
                    case "8": exportCsv(connection); break;
                    case "9": showActivity(connection); break;
                    case "0":
                        System.out.println("Goodbye.");
                        return;
                    default: System.out.println("Choose a listed menu option.");
                }
            } catch (SQLException e) {
                System.out.println("Operation failed: " + e.getMessage());
            } catch (IOException e) {
                System.out.println("File operation failed: " + e.getMessage());
            }
        }
    }

    private void dashboard(Connection connection) throws SQLException {
        String query = "SELECT COUNT(*) total, " +
                "COALESCE(SUM(status = 'ACTIVE'), 0) active, " +
                "COALESCE(SUM(status = 'PAUSED'), 0) paused, " +
                "COALESCE(SUM(status = 'CANCELLED'), 0) cancelled FROM customers";
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(query)) {
            result.next();
            System.out.println("\nSUBSCRIBER DASHBOARD");
            System.out.println("Total: " + result.getInt("total"));
            System.out.println("Active: " + result.getInt("active"));
            System.out.println("Paused: " + result.getInt("paused"));
            System.out.println("Cancelled: " + result.getInt("cancelled"));
        }
    }

    private void listSubscribers(Connection connection) throws SQLException {
        int total = subscriberCount(connection, "");
        if (total == 0) {
            System.out.println("No subscribers found.");
            return;
        }
        int pages = (total + PAGE_SIZE - 1) / PAGE_SIZE;
        int page = readInt("Page (1-" + pages + "): ", 1, pages);
        String query = "SELECT id, name, email, phone, company, status, created_at " +
                "FROM customers ORDER BY id LIMIT ? OFFSET ?";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setInt(1, PAGE_SIZE);
            statement.setInt(2, (page - 1) * PAGE_SIZE);
            try (ResultSet result = statement.executeQuery()) {
                printTableHeader();
                while (result.next()) {
                    printSubscriber(result);
                }
                printDivider();
                System.out.println("Page " + page + "/" + pages + " | " + total + " subscriber(s)");
            }
        }
    }

    private int subscriberCount(Connection connection, String search) throws SQLException {
        String query = "SELECT COUNT(*) FROM customers" +
                (search.isEmpty() ? "" : " WHERE name LIKE ? OR email LIKE ? OR phone LIKE ? OR company LIKE ?");
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            if (!search.isEmpty()) {
                String pattern = "%" + search + "%";
                for (int i = 1; i <= 4; i++) statement.setString(i, pattern);
            }
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private void addSubscriber(Connection connection) throws SQLException {
        String name = required("Name", 100);
        String email = validEmail();
        String phone = required("Phone", 30);
        String company = required("Company / locality", 100);
        String query = "INSERT INTO customers (name, email, phone, company, status) VALUES (?, ?, ?, ?, 'ACTIVE')";
        int[] subscriberId = new int[1];
        try {
            inTransaction(connection, () -> {
                try (PreparedStatement statement = connection.prepareStatement(query, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, name);
                    statement.setString(2, email);
                    statement.setString(3, phone);
                    statement.setString(4, company);
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("Could not retrieve the new subscriber ID.");
                        subscriberId[0] = keys.getInt(1);
                    }
                }
                audit(connection, "CREATE", subscriberId[0], "Created subscriber " + name);
            });
            System.out.println("Subscriber created (ID " + subscriberId[0] + ").");
        } catch (SQLException e) {
            if ("23000".equals(e.getSQLState())) {
                System.out.println("A subscriber with that email address already exists.");
                return;
            }
            throw e;
        }
    }

    private void searchSubscribers(Connection connection) throws SQLException {
        String term = prompt("Search name, email, phone, or company: ").trim();
        if (term.isEmpty()) {
            System.out.println("Search text cannot be blank.");
            return;
        }
        String query = "SELECT id, name, email, phone, company, status, created_at FROM customers " +
                "WHERE name LIKE ? OR email LIKE ? OR phone LIKE ? OR company LIKE ? ORDER BY name, id LIMIT 100";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            String pattern = "%" + term + "%";
            for (int i = 1; i <= 4; i++) statement.setString(i, pattern);
            try (ResultSet result = statement.executeQuery()) {
                printTableHeader();
                boolean found = false;
                while (result.next()) {
                    found = true;
                    printSubscriber(result);
                }
                printDivider();
                if (!found) System.out.println("No matching subscribers found.");
                else System.out.println("Showing up to 100 matches.");
            }
        }
    }

    private void updateSubscriber(Connection connection) throws SQLException {
        int id = readPositiveInt("Subscriber ID: ");
        String find = "SELECT name, email, phone, company FROM customers WHERE id = ?";
        String name;
        String email;
        String phone;
        String company;
        try (PreparedStatement statement = connection.prepareStatement(find)) {
            statement.setInt(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    System.out.println("No subscriber found with ID " + id + ".");
                    return;
                }
                name = result.getString("name");
                email = result.getString("email");
                phone = result.getString("phone");
                company = result.getString("company");
            }
        }

        System.out.println("Leave a field blank to keep its current value.");
        name = optionalValue("Name", name, 100);
        email = optionalValue("Email", email, 254);
        if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            System.out.println("Enter a valid email address.");
            return;
        }
        phone = optionalValue("Phone", phone, 30);
        company = optionalValue("Company / locality", company, 100);
        final String updatedName = name;
        final String updatedEmail = email;
        final String updatedPhone = phone;
        final String updatedCompany = company;
        String query = "UPDATE customers SET name = ?, email = ?, phone = ?, company = ? WHERE id = ?";
        try {
            inTransaction(connection, () -> {
                try (PreparedStatement statement = connection.prepareStatement(query)) {
                    statement.setString(1, updatedName);
                    statement.setString(2, updatedEmail);
                    statement.setString(3, updatedPhone);
                    statement.setString(4, updatedCompany);
                    statement.setInt(5, id);
                    statement.executeUpdate();
                }
                audit(connection, "UPDATE", id, "Updated subscriber details");
            });
        } catch (SQLException e) {
            if ("23000".equals(e.getSQLState())) {
                System.out.println("A different subscriber already uses that email address.");
                return;
            }
            throw e;
        }
        System.out.println("Subscriber updated.");
    }

    private void changeStatus(Connection connection) throws SQLException {
        int id = readPositiveInt("Subscriber ID: ");
        System.out.println("1. ACTIVE  2. PAUSED  3. CANCELLED");
        int selection = readInt("New status: ", 1, STATUSES.length);
        final String status = STATUSES[selection - 1];
        int[] affected = new int[1];
        inTransaction(connection, () -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE customers SET status = ? WHERE id = ?")) {
                statement.setString(1, status);
                statement.setInt(2, id);
                affected[0] = statement.executeUpdate();
            }
            if (affected[0] > 0) audit(connection, "STATUS", id, "Changed status to " + status);
        });
        if (affected[0] == 0) {
            System.out.println("No subscriber found with ID " + id + ".");
            return;
        }
        System.out.println("Subscriber status changed to " + status + ".");
    }

    private void deleteSubscriber(Connection connection) throws SQLException {
        int id = readPositiveInt("Subscriber ID to permanently delete: ");
        System.out.print("Type DELETE to confirm: ");
        if (!"DELETE".equals(prompt("").trim())) {
            System.out.println("Deletion cancelled.");
            return;
        }
        String[] name = new String[1];
        int[] deleted = new int[1];
        inTransaction(connection, () -> {
            try (PreparedStatement lookup = connection.prepareStatement("SELECT name FROM customers WHERE id = ?")) {
                lookup.setInt(1, id);
                try (ResultSet result = lookup.executeQuery()) {
                    if (!result.next()) return;
                    name[0] = result.getString("name");
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM customers WHERE id = ?")) {
                statement.setInt(1, id);
                deleted[0] = statement.executeUpdate();
            }
            if (deleted[0] > 0) audit(connection, "DELETE", id, "Deleted subscriber " + name[0]);
        });
        if (deleted[0] == 0) {
            System.out.println("No subscriber found with ID " + id + ".");
            return;
        }
        System.out.println("Subscriber permanently deleted.");
    }

    private void exportCsv(Connection connection) throws SQLException, IOException {
        String fileName = prompt("CSV file path (default: subscribers.csv): ").trim();
        if (fileName.isEmpty()) fileName = "subscribers.csv";
        Path path = Paths.get(fileName).toAbsolutePath().normalize();
        if (Files.exists(path)) {
            String answer = prompt("File exists. Overwrite? (y/N): ").trim();
            if (!"y".equalsIgnoreCase(answer)) {
                System.out.println("Export cancelled.");
                return;
            }
        }
        String query = "SELECT id, name, email, phone, company, status, created_at FROM customers ORDER BY id";
        try (PreparedStatement statement = connection.prepareStatement(query);
             ResultSet result = statement.executeQuery();
             java.io.BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("id,name,email,phone,company,status,created_at");
            writer.newLine();
            while (result.next()) {
                writer.write(result.getInt("id") + "," + csv(result.getString("name")) + "," +
                        csv(result.getString("email")) + "," + csv(result.getString("phone")) + "," +
                        csv(result.getString("company")) + "," + csv(result.getString("status")) + "," +
                        csv(result.getTimestamp("created_at").toString()));
                writer.newLine();
            }
        }
        audit(connection, "EXPORT", null, "Exported subscribers to " + path.getFileName());
        System.out.println("Exported subscribers to " + path);
    }

    private void showActivity(Connection connection) throws SQLException {
        String query = "SELECT username, action, subscriber_id, details, occurred_at " +
                "FROM audit_log JOIN users ON audit_log.user_id = users.id " +
                "ORDER BY audit_log.id DESC LIMIT 25";
        try (PreparedStatement statement = connection.prepareStatement(query);
             ResultSet result = statement.executeQuery()) {
            System.out.println("\nRECENT ACTIVITY (latest 25)");
            while (result.next()) {
                Timestamp timestamp = result.getTimestamp("occurred_at");
                System.out.printf("%s | %-12s | %-20s | ID: %-6s | %s%n",
                        timestamp, result.getString("username"), result.getString("action"),
                        result.getObject("subscriber_id") == null ? "-" : result.getInt("subscriber_id"),
                        result.getString("details"));
            }
        }
    }

    private void audit(Connection connection, String action, Integer subscriberId, String details)
            throws SQLException {
        String query = "INSERT INTO audit_log (user_id, action, subscriber_id, details) VALUES (?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setInt(1, currentUserId);
            statement.setString(2, action);
            if (subscriberId == null) statement.setNull(3, java.sql.Types.INTEGER);
            else statement.setInt(3, subscriberId);
            statement.setString(4, details);
            statement.executeUpdate();
        }
    }

    private void inTransaction(Connection connection, DatabaseWork work) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            work.run();
            connection.commit();
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException rollbackError) {
                e.addSuppressed(rollbackError);
            }
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private interface DatabaseWork {
        void run() throws SQLException;
    }

    private String required(String label, int maxLength) {
        while (true) {
            String value = prompt(label + ": ").trim();
            if (value.isEmpty()) {
                System.out.println(label + " is required.");
            } else if (value.length() > maxLength) {
                System.out.println(label + " must be at most " + maxLength + " characters.");
            } else {
                return value;
            }
        }
    }

    private String validEmail() {
        while (true) {
            String email = required("Email", 254);
            if (email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) return email;
            System.out.println("Enter a valid email address.");
        }
    }

    private String optionalValue(String label, String current, int maxLength) {
        while (true) {
            String value = prompt(label + " [" + current + "]: ").trim();
            if (value.isEmpty()) return current;
            if (value.length() > maxLength) {
                System.out.println(label + " must be at most " + maxLength + " characters.");
            } else {
                return value;
            }
        }
    }

    private int readPositiveInt(String label) {
        return readInt(label, 1, Integer.MAX_VALUE);
    }

    private int readInt(String label, int minimum, int maximum) {
        while (true) {
            try {
                int value = Integer.parseInt(prompt(label).trim());
                if (value >= minimum && value <= maximum) return value;
            } catch (NumberFormatException ignored) {
                // Re-prompt below for invalid terminal input.
            }
            System.out.println("Enter a number between " + minimum + " and " + maximum + ".");
        }
    }

    private String prompt(String label) {
        if (console != null) {
            String value = console.readLine("%s", label);
            if (value == null) throw new IllegalStateException("Terminal input was closed.");
            return value;
        }
        System.out.print(label);
        return scanner.nextLine();
    }

    private String readPassword(String label) {
        if (console != null) {
            char[] password = console.readPassword("%s", label);
            if (password == null) return "";
            try {
                return new String(password);
            } finally {
                Arrays.fill(password, '\0');
            }
        }
        return prompt(label);
    }

    private String readNewPassword(String label) {
        while (true) {
            String password = readPassword(label + " (12-1024 characters): ");
            if (!validPasswordLength(password)) {
                System.out.println("Use between 12 and 1024 characters.");
                continue;
            }
            String confirmation = readPassword("Confirm password: ");
            if (password.equals(confirmation)) return password;
            System.out.println("Passwords do not match.");
        }
    }

    private static boolean validPasswordLength(String password) {
        return password.length() >= 12 && password.length() <= 1024;
    }

    private static String hashPassword(String password) throws GeneralSecurityException {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        char[] passwordChars = password.toCharArray();
        byte[] key;
        try {
            key = deriveKey(passwordChars, salt, PASSWORD_ITERATIONS);
        } finally {
            Arrays.fill(passwordChars, '\0');
        }
        return "PBKDF2$" + PASSWORD_ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) +
                "$" + Base64.getEncoder().encodeToString(key);
    }

    private static byte[] deriveKey(char[] password, byte[] salt, int iterations) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value.replace("\"", "\"\"");
        int firstNonWhitespace = 0;
        while (firstNonWhitespace < safe.length() && Character.isWhitespace(safe.charAt(firstNonWhitespace))) {
            firstNonWhitespace++;
        }
        if (firstNonWhitespace < safe.length() && "=+-@".indexOf(safe.charAt(firstNonWhitespace)) >= 0) {
            safe = "'" + safe;
        }
        return "\"" + safe + "\"";
    }

    private static String setting(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static void printTableHeader() {
        printDivider();
        System.out.printf("%-6s | %-20s | %-30s | %-18s | %-20s | %-10s | %-19s%n",
                "ID", "Name", "Email", "Phone", "Company", "Status", "Created");
        printDivider();
    }

    private static void printSubscriber(ResultSet result) throws SQLException {
        System.out.printf("%-6d | %-20.20s | %-30.30s | %-18.18s | %-20.20s | %-10s | %-19s%n",
                result.getInt("id"), result.getString("name"), result.getString("email"),
                result.getString("phone"), result.getString("company"), result.getString("status"),
                result.getTimestamp("created_at"));
    }

    private static void printDivider() {
        System.out.println("-------------------------------------------------------------------------------------------------------------------------------");
    }
}
