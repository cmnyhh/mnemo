package com.hs.memory.admin;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCredentialStore implements CredentialStore {

    private final JdbcTemplate jdbc;

    public JdbcCredentialStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Credential load() {
        List<Credential> rows = jdbc.query(
                "select username, password_hash from admin_credential where id = true",
                (rs, i) -> new Credential(rs.getString("username"), rs.getString("password_hash")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public void save(String username, String hash) {
        jdbc.update("""
                insert into admin_credential (id, username, password_hash) values (true, ?, ?)
                on conflict (id) do update
                   set username = excluded.username,
                       password_hash = excluded.password_hash,
                       updated_at = now()
                """, username, hash);
    }
}
