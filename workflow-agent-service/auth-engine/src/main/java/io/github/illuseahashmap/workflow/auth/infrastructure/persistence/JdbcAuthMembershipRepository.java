package io.github.illuseahashmap.workflow.auth.infrastructure.persistence;

import io.github.illuseahashmap.workflow.auth.domain.AuthMembershipRepository;
import io.github.illuseahashmap.workflow.shared.model.PageSlice;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuthMembershipRepository implements AuthMembershipRepository {

    private static final String SELECT_MEMBERSHIP = """
            SELECT membership.user_id, tenant.tenant_id, tenant.tenant_code, tenant.tenant_name,
                   membership.enabled AS membership_enabled, tenant.enabled AS tenant_enabled,
                   membership.joined_at
            FROM auth_user_tenant membership
            JOIN workflow_tenant tenant ON tenant.tenant_code = membership.tenant_code
            """;

    private final JdbcClient jdbcClient;

    public JdbcAuthMembershipRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<TenantMembership> findByUserId(String userId) {
        return jdbcClient.sql(SELECT_MEMBERSHIP + " WHERE membership.user_id = :userId ORDER BY tenant.tenant_name")
                .param("userId", userId)
                .query(this::mapRow)
                .list();
    }

    @Override
    public Optional<TenantMembership> find(String userId, String tenantCode) {
        return jdbcClient.sql(SELECT_MEMBERSHIP + """
                        WHERE membership.user_id = :userId AND membership.tenant_code = :tenantCode
                        """)
                .param("userId", userId)
                .param("tenantCode", tenantCode)
                .query(this::mapRow)
                .optional();
    }

    @Override
    public List<TenantMember> findMembers(String tenantCode, String keyword) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        return jdbcClient.sql("""
                        SELECT users.user_id, users.username, users.display_name,
                               users.enabled AS user_enabled, membership.enabled AS membership_enabled,
                               membership.joined_at
                        FROM auth_user_tenant membership
                        JOIN auth_user users ON users.user_id = membership.user_id
                        WHERE membership.tenant_code = :tenantCode
                          AND (:keyword = '' OR users.username ILIKE :pattern OR users.display_name ILIKE :pattern)
                        ORDER BY users.display_name, users.username
                        """)
                .param("tenantCode", tenantCode)
                .param("keyword", normalizedKeyword)
                .param("pattern", "%" + normalizedKeyword + "%")
                .query((resultSet, rowNumber) -> new TenantMember(
                        resultSet.getString("user_id"),
                        resultSet.getString("username"),
                        resultSet.getString("display_name"),
                        resultSet.getInt("user_enabled") == 1,
                        resultSet.getInt("membership_enabled") == 1,
                        resultSet.getObject("joined_at", java.time.OffsetDateTime.class)))
                .list();
    }

    @Override
    public PageSlice<TenantMember> pageEnabledMembers(
            String tenantCode, String keyword, int pageNumber, int pageSize) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        String filter = """
                FROM auth_user_tenant membership
                JOIN auth_user users ON users.user_id = membership.user_id
                WHERE membership.tenant_code = :tenantCode
                  AND membership.enabled = 1
                  AND users.enabled = 1
                  AND (:keyword = '' OR users.username ILIKE :pattern OR users.display_name ILIKE :pattern)
                """;
        long total = jdbcClient.sql("SELECT COUNT(*) " + filter)
                .param("tenantCode", tenantCode)
                .param("keyword", normalizedKeyword)
                .param("pattern", "%" + normalizedKeyword + "%")
                .query(Long.class)
                .single();
        List<TenantMember> records = jdbcClient.sql("""
                        SELECT users.user_id, users.username, users.display_name,
                               users.enabled AS user_enabled, membership.enabled AS membership_enabled,
                               membership.joined_at
                        """ + filter + """
                        ORDER BY users.display_name, users.username
                        LIMIT :pageSize OFFSET :offset
                        """)
                .param("tenantCode", tenantCode)
                .param("keyword", normalizedKeyword)
                .param("pattern", "%" + normalizedKeyword + "%")
                .param("pageSize", pageSize)
                .param("offset", (pageNumber - 1) * pageSize)
                .query(this::mapMember)
                .list();
        return new PageSlice<>(total, pageNumber, pageSize, records);
    }

    @Override
    public Set<String> findEnabledUsernames(String tenantCode, Collection<String> usernames) {
        if (usernames == null || usernames.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbcClient.sql("""
                        SELECT users.username
                        FROM auth_user_tenant membership
                        JOIN auth_user users ON users.user_id = membership.user_id
                        WHERE membership.tenant_code = :tenantCode
                          AND membership.enabled = 1
                          AND users.enabled = 1
                          AND users.username IN (:usernames)
                        """)
                .param("tenantCode", tenantCode)
                .param("usernames", usernames)
                .query(String.class)
                .list());
    }

    @Override
    public List<UserAvailability> findUserAvailability(String tenantCode, Collection<String> usernames) {
        if (usernames == null || usernames.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql("""
                        SELECT users.username,
                               users.enabled AS user_enabled,
                               membership.user_id IS NOT NULL AS membership_exists,
                               COALESCE(membership.enabled, 0) AS membership_enabled
                        FROM auth_user users
                        LEFT JOIN auth_user_tenant membership
                          ON membership.user_id = users.user_id
                         AND membership.tenant_code = :tenantCode
                        WHERE users.username IN (:usernames)
                        """)
                .param("tenantCode", tenantCode)
                .param("usernames", usernames)
                .query((resultSet, rowNumber) -> new UserAvailability(
                        resultSet.getString("username"),
                        resultSet.getInt("user_enabled") == 1,
                        resultSet.getBoolean("membership_exists"),
                        resultSet.getInt("membership_enabled") == 1))
                .list();
    }

    @Override
    public void add(String userId, String tenantCode) {
        jdbcClient.sql("""
                        INSERT INTO auth_user_tenant (user_id, tenant_code)
                        VALUES (:userId, :tenantCode)
                        ON CONFLICT (user_id, tenant_code)
                        DO UPDATE SET enabled = 1, updated_at = CURRENT_TIMESTAMP
                        """)
                .param("userId", userId)
                .param("tenantCode", tenantCode)
                .update();
    }

    @Override
    public void updateEnabled(String userId, String tenantCode, boolean enabled) {
        jdbcClient.sql("""
                        UPDATE auth_user_tenant
                        SET enabled = :enabled, updated_at = CURRENT_TIMESTAMP
                        WHERE user_id = :userId AND tenant_code = :tenantCode
                        """)
                .param("enabled", enabled ? 1 : 0)
                .param("userId", userId)
                .param("tenantCode", tenantCode)
                .update();
    }

    @Override
    public void lockTenantMemberships(String tenantCode) {
        jdbcClient.sql("""
                        SELECT user_id
                        FROM auth_user_tenant
                        WHERE tenant_code = :tenantCode
                        FOR UPDATE
                        """)
                .param("tenantCode", tenantCode)
                .query(String.class)
                .list();
    }

    private TenantMembership mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new TenantMembership(
                resultSet.getString("user_id"),
                resultSet.getString("tenant_id"),
                resultSet.getString("tenant_code"),
                resultSet.getString("tenant_name"),
                resultSet.getInt("membership_enabled") == 1,
                resultSet.getInt("tenant_enabled") == 1,
                resultSet.getObject("joined_at", java.time.OffsetDateTime.class)
        );
    }

    private TenantMember mapMember(ResultSet resultSet, int rowNumber) throws SQLException {
        return new TenantMember(
                resultSet.getString("user_id"),
                resultSet.getString("username"),
                resultSet.getString("display_name"),
                resultSet.getInt("user_enabled") == 1,
                resultSet.getInt("membership_enabled") == 1,
                resultSet.getObject("joined_at", java.time.OffsetDateTime.class));
    }
}
