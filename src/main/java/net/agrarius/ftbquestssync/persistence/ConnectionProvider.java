package net.agrarius.ftbquestssync.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.agrarius.ftbquestssync.config.Config;

import java.sql.Connection;
import java.sql.SQLException;

/** Owns the HikariCP pool lifecycle for the MySQL backend. */
public final class ConnectionProvider {
    private HikariDataSource dataSource;

    /** Builds the pool from Config and opens it. Caller guarantees password is present. */
    public void open() throws Exception {
        HikariConfig hc = new HikariConfig();
        hc.setDriverClassName("com.mysql.cj.jdbc.Driver");
        hc.setJdbcUrl(String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=%s&requireSSL=%s&allowPublicKeyRetrieval=%s",
                Config.mysqlHost, Config.mysqlPort, Config.mysqlDatabase,
                Config.mysqlUseSsl, Config.mysqlUseSsl, Config.mysqlAllowPublicKeyRetrieval));
        hc.setUsername(Config.mysqlUsername);
        hc.setPassword(Config.mysqlPassword);
        hc.setMaximumPoolSize(Math.max(1, Config.mysqlMaxPool));
        hc.setMinimumIdle(Math.max(0, Config.mysqlMinIdle));
        hc.setConnectionTimeout(1_000L);
        hc.setPoolName("FTBQuestsSync-Pool");
        Class.forName("com.mysql.cj.jdbc.Driver");
        dataSource = new HikariDataSource(hc);
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public boolean isAvailable() {
        return dataSource != null && !dataSource.isClosed();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
