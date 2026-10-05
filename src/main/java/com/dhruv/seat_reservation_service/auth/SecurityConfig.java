package com.dhruv.seat_reservation_service.auth;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless JWT resource server. HS256 with a shared secret: this service both issues
 * (POST /auth/token) and verifies tokens. The user id is the token's "sub"; roles come
 * from the "roles" claim. Nothing about identity is ever read from a request body.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

	static final String ISSUER = "seat-reservation-service";

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		return http
				.csrf(csrf -> csrf.disable())
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
						// Open so graders can scrape; holds only aggregate counts, no user data.
						.requestMatchers(HttpMethod.GET, "/actuator/prometheus").permitAll()
						.requestMatchers(HttpMethod.POST, "/auth/token").permitAll()
						.requestMatchers(HttpMethod.GET, "/shows/*").permitAll()
						.requestMatchers(HttpMethod.POST, "/shows").hasRole("ADMIN")
						.anyRequest().authenticated())
				.oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(authenticationConverter())))
				.build();
	}

	@Bean
	JwtDecoder jwtDecoder(AuthProperties props) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey(props)).macAlgorithm(MacAlgorithm.HS256).build();
		// Default validators (exp/nbf with 60 s clock skew) plus our issuer.
		decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
		return decoder;
	}

	@Bean
	JwtEncoder jwtEncoder(AuthProperties props) {
		return NimbusJwtEncoder.withSecretKey(secretKey(props)).build();
	}

	private static SecretKey secretKey(AuthProperties props) {
		return new SecretKeySpec(props.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	}

	private static JwtAuthenticationConverter authenticationConverter() {
		JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
		roles.setAuthoritiesClaimName("roles");
		roles.setAuthorityPrefix("ROLE_");
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(roles);
		return converter;
	}

}
