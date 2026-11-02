package com.lockerline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lockerline.door.DoorRepository;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:pickup-test")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PickupTest {

    static final Instant T = Instant.parse("2026-09-25T13:10:00Z");

    @TestConfiguration
    static class FixedClock {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(T, ZoneOffset.UTC);
        }
    }

    @Autowired
    MockMvc http;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObjectMapper mapper;

    @MockitoSpyBean
    DoorRepository doors;

    private String drop() throws Exception {
        String body = http.perform(post("/drops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sizeClass\":\"M\",\"flat\":\"302\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return mapper.readTree(body).path("code").asText();
    }

    private org.springframework.test.web.servlet.ResultActions pickup(String code) throws Exception {
        return http.perform(post("/pickups")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}"));
    }

    @Test
    void aPickupOpensTheDoorAndReturnsItsResponse() throws Exception {
        String code = drop();

        pickup(code)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parcelId").value("P001"))
                .andExpect(jsonPath("$.door").exists())
                .andExpect(jsonPath("$.openedAt").value("2026-09-25T13:10:00Z"))
                .andExpect(jsonPath("$.dwellSeconds").value(0));

        assertThat(jdbc.sql("SELECT state FROM parcels WHERE id = 1")
                .query(String.class).single()).isEqualTo("collected");

        assertThat(jdbc.sql("SELECT code_used_at FROM parcels WHERE id = 1")
                .query(Instant.class).single()).isNotNull();

        assertThat(jdbc.sql("SELECT current_parcel_id FROM doors WHERE id = "
                        + "(SELECT door_id FROM parcels WHERE id = 1)")
                .query(Long.class).optional()).isEmpty();
    }

    @Test
    void usingTheSameCodeAgainReturns409CodeUsed() throws Exception {
        String code = drop();

        pickup(code)
                .andExpect(status().isOk());

        pickup(code)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/code-used"));
    }

    @Test
    void aFailureFreeingTheDoorRollsBackTheParcelUpdate() throws Exception {
        String code = drop();

        doThrow(new IllegalStateException("Simulated door failure"))
                .when(doors).free(anyLong());

        pickup(code)
                .andExpect(status().isInternalServerError());

        String state = jdbc.sql("SELECT state FROM parcels WHERE id = 1")
                .query(String.class)
                .single();

        Instant codeUsedAt = jdbc.sql("SELECT code_used_at FROM parcels WHERE id = 1")
                .query(Instant.class)
                .optional()
                .orElse(null);

        Long currentParcelId = jdbc.sql(
                        "SELECT current_parcel_id FROM doors WHERE id = "
                                + "(SELECT door_id FROM parcels WHERE id = 1)")
                .query(Long.class)
                .single();

        assertThat(state).isEqualTo("holding");
        assertThat(codeUsedAt).isNull();
        assertThat(currentParcelId).isEqualTo(1L);
    }
}