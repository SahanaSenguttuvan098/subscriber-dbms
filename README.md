# Subscriber Management System

A Java and MySQL terminal application for managing subscriber records. It includes first-run administrator setup, secure password storage, subscriber search and lifecycle status, paginated listings, an activity log, and CSV export.

## Requirements

- Java 8 or later
- MySQL 8.0 or later
- The MySQL Connector/J driver included in `lib`

## Database setup

1. Start MySQL.
2. Open a terminal in this project folder and apply the schema:

   ```powershell
   cmd /c "mysql -u root -p < schema.sql"
   ```

3. For an existing installation of the original project, back up `customer_db` first, then run `cmd /c "mysql -u root -p < migration.sql"` instead of `schema.sql`. The migration is intended to run once. Resolve duplicate usernames or subscriber email addresses before applying it.

For production, use a dedicated MySQL account rather than the root account. Grant it access only to `customer_db`.

## Run

Set the connection settings in PowerShell, then launch the project:

```powershell
admin
```

`MYSQL_PASSWORD` is also accepted as a password variable for compatibility with the VS Code launch configuration. Never put database passwords in source control.

On the first launch, the application asks you to create an administrator account. Use a password with at least 12 characters. Passwords are stored as salted PBKDF2 hashes. Existing accounts are upgraded from plaintext to hashes the first time they sign in after applying `migration.sql`; short legacy passwords must be changed during that sign-in.

## Menu

| Option | Description |
| --- | --- |
| Dashboard | Counts total, active, paused, and cancelled subscribers |
| List subscribers | Shows 10 records per page |
| Add subscriber | Creates an active subscriber; email addresses must be unique |
| Search subscribers | Searches name, email, phone, and company |
| Update subscriber | Updates fields; blank input keeps the current value |
| Change subscriber status | Marks a subscriber active, paused, or cancelled |
| Delete subscriber | Permanently removes a record after an explicit confirmation |
| Export subscribers to CSV | Writes a UTF-8 CSV file and asks before overwriting |
| Recent activity | Displays the latest 25 recorded actions |

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_URL` | `jdbc:mysql://localhost:3306/customer_db` | JDBC connection URL |
| `DB_USER` | `root` | MySQL account |
| `DB_PASSWORD` | — | MySQL password |
| `MYSQL_PASSWORD` | — | Compatibility fallback for `DB_PASSWORD` |

The application does not create or alter the database automatically. Apply `schema.sql` (or the one-time migration) before running it.
