package com.team.silveragent;

import com.team.silveragent.domain.tool.RouteGuideTool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:user-schema-tests;DB_CLOSE_DELAY=-1",
        "agent.model.enabled=false"})
class UserSchemaTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired RouteGuideTool routes;

    @Test
    @Transactional
    void phoneIsOptionalTextAndRemovedColumnsAreAbsent() {
        assertThat(jdbc.queryForList("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='USERS'", String.class))
                .contains("PHONE").doesNotContain("HOME_LONGITUDE", "HOME_LATITUDE", "FAMILY_MEMBER");
        jdbc.update("UPDATE users SET phone=? WHERE id=?", "+86 13800138000", "user-001");
        assertThat(jdbc.queryForObject("SELECT phone FROM users WHERE id='user-001'", String.class))
                .isEqualTo("+86 13800138000");
        jdbc.update("UPDATE users SET phone=NULL WHERE id='user-001'");
        assertThat(jdbc.queryForObject("SELECT phone FROM users WHERE id='user-001'", String.class)).isNull();
    }
    @Test
    void upgradesExistingUsersWithoutLosingPhoneOnRestart() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:user-schema-upgrade;DB_CLOSE_DELAY=-1", "sa", "");
        var db = new JdbcTemplate(source);
        db.execute("CREATE TABLE users(id VARCHAR(40) PRIMARY KEY, name VARCHAR(40) NOT NULL, home_address VARCHAR(200), preferred_transport VARCHAR(40), home_longitude DOUBLE PRECISION, home_latitude DOUBLE PRECISION, family_member VARCHAR(40), CONSTRAINT fk_users_family_member FOREIGN KEY(family_member) REFERENCES users(id))");
        db.update("INSERT INTO users(id,name,home_longitude,home_latitude) VALUES ('elder','老人',116,39),('family','家属',NULL,NULL)");
        var schema = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
        schema.execute(source);
        db.update("UPDATE users SET phone='+8613800138000' WHERE id='elder'");
        schema.execute(source);
        assertThat(db.queryForObject("SELECT phone FROM users WHERE id='elder'", String.class)).isEqualTo("+8613800138000");
        assertThat(db.queryForList("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='USERS'", String.class))
                .doesNotContain("HOME_LONGITUDE", "HOME_LATITUDE", "FAMILY_MEMBER");
    }

    @Test
    void upgradesLegacyContactsAndPreservesNotificationIdentity() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:contact-schema-upgrade;DB_CLOSE_DELAY=-1", "sa", "");
        var db = new JdbcTemplate(source);
        db.execute("CREATE TABLE users(id VARCHAR(40) PRIMARY KEY, name VARCHAR(40) NOT NULL, home_address VARCHAR(200), preferred_transport VARCHAR(40))");
        db.update("INSERT INTO users(id,name) VALUES ('elder','老人'),('family','家属')");
        db.execute("CREATE TABLE family_contacts(id VARCHAR(40) PRIMARY KEY,user_id VARCHAR(40),name VARCHAR(40),relationship VARCHAR(40),phone VARCHAR(30))");
        db.update("INSERT INTO family_contacts VALUES ('contact-1','elder','家属','女儿','13800001234')");
        db.execute("CREATE TABLE care_relations(id VARCHAR(40) PRIMARY KEY,caregiver_id VARCHAR(40),elder_user_id VARCHAR(40),role VARCHAR(20),relationship VARCHAR(40))");
        db.update("INSERT INTO care_relations VALUES ('relation-1','family','elder','FAMILY','女儿')");
        var schema = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
        schema.execute(source);
        schema.execute(source);
        assertThat(db.queryForList("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='FAMILY_CONTACTS'", String.class))
                .containsExactlyInAnyOrder("ID", "USER", "CONTACT");
        assertThat(db.queryForObject("SELECT contact FROM family_contacts WHERE id='contact-1'", String.class)).isEqualTo("family");
        assertThat(db.queryForObject("SELECT \"USER\" FROM family_contacts WHERE id='contact-1'", String.class)).isEqualTo("elder");
        assertThat(db.queryForObject("SELECT phone FROM users WHERE id='family'", String.class)).isEqualTo("13800001234");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> db.update("UPDATE family_contacts SET contact='missing'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void routeOriginComesFromRouteGeometry() {
        var route = routes.plan("schema-route-test", "user-001", "h001",
                LocalDateTime.now().plusDays(2), "公交");
        assertThat(route.origin()).isEqualTo(route.polyline().get(0));
        assertThat(route.origin().longitude()).isEqualTo(116.365300);
        assertThat(route.destination().longitude()).isEqualTo(116.397428);
        assertThat(route.steps()).isNotEmpty();
        assertThat(route.source()).isEqualTo("SIMULATED");
    }
}
