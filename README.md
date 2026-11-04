Subscriber Database Management System

A Java terminal application for managing subscriber records in MySQL. Sign in with an administrator account to create, search, update, and track subscribers from an interactive command-line menu.

## Features

- Create, search, view, update, and delete subscriber records.
- Browse subscribers in a paginated table.
- View a dashboard with total, active, paused, and cancelled subscriber counts.
- Track subscriber statuses: Active, Paused, and Cancelled.
- Export subscriber records to a UTF-8 CSV file.
- Review recent activity recorded by the application.
- Protect administrator passwords with salted PBKDF2 hashes.
- Record subscriber changes and their audit entries together.

## Requirements

- Java 8 or later, including `java` and `javac`.
- MySQL Server 8.0.16 or later.
- MySQL Connector/J 8.4.0, included at `lib\mysql-connector-j-8.4.0.jar`.

## Set up the database

1. Start MySQL Server.
2. For a new installation, open PowerShell in the project directory and run:

   ```powershell
   cmd /c "mysql -u root -p < schema.sql"
   ```

   Alternatively, open `schema.sql` in MySQL Workbench and execute it. This creates the `customer_db` database and its tables if they do not already exist.

3. If you have data from the original version of this project, back up `customer_db` first, then apply `migration.sql` instead of `schema.sql`:

   ```powershell
   cmd /c "mysql -u root -p < migration.sql"
   ```

   The migration is intended to run once. It preserves existing subscriber and user records, adds status and activity-history fields, and upgrades legacy passwords to secure hashes when those users next sign in. Fix duplicate usernames or email addresses before migrating if necessary.

Do not run the migration on a fresh database. The application does not create or alter database tables when it starts.

For regular or production use, create a dedicated MySQL account with access only to `customer_db` instead of using the MySQL root account.

## Configure the database connection

By default, the application connects to `jdbc:mysql://localhost:3306/customer_db` as user `root`. Override these settings with environment variables:

| Setting | Environment variable | Default |
| --- | --- | --- |
| JDBC URL | `DB_URL` | `jdbc:mysql://localhost:3306/customer_db` |
| Username | `DB_USER` | `root` |
| Password | `DB_PASSWORD` | Not set |
| Password compatibility fallback | `MYSQL_PASSWORD` | Not set |

`DB_PASSWORD` takes precedence. Set a password before launching; the application exits with an error if neither password variable is set. Do not save database passwords in source control.

## Compile and run on Windows

Open PowerShell in the project directory, set the MySQL connection settings, and run the launcher:

```powershell
$env:DB_URL = "jdbc:mysql://localhost:3306/customer_db"
$env:DB_USER = "root"
$env:DB_PASSWORD = "your-mysql-password"
.\run.bat
```

`run.bat` compiles `SubscriberApp.java` using the included MySQL driver, then starts the terminal application. You can also launch it from VS Code using the **Run Subscriber** configuration; VS Code prompts for the database password.

On the first launch, create an administrator username and password when prompted. Use a password between 12 and 1024 characters. The administrator login is separate from your MySQL login. In an existing installation, legacy plaintext passwords are replaced with secure hashes at sign-in; passwords shorter than 12 characters must be changed then.

## Main menu

| Option | Description |
| --- | --- |
| 1. Dashboard | Shows subscriber totals by status |
| 2. List subscribers | Displays 10 records per page |
| 3. Add subscriber | Creates a subscriber with Active status |
| 4. Search subscribers | Searches names, emails, phone numbers, and companies |
| 5. Update subscriber | Updates subscriber details; blank fields keep their current values |
| 6. Change subscriber status | Marks a subscriber Active, Paused, or Cancelled |
| 7. Delete subscriber | Permanently deletes a record after you type `DELETE` |
| 8. Export subscribers to CSV | Writes a CSV file and asks before overwriting an existing file |
| 9. Recent activity | Shows the latest 25 recorded actions |
| 0. Exit | Closes the application |

## Source files

- `SubscriberApp.java` — application entry point, terminal menu, authentication, subscriber operations, CSV export, and activity logging.
- `schema.sql` — database and tables for a new installation.
- `migration.sql` — one-time schema migration for the original project database.
- `run.bat` — Windows compile-and-run script.
- `lib\mysql-connector-j-8.4.0.jar` — MySQL JDBC driver.
