package ee.ria.govsso.client.configuration;

import ee.ria.govsso.client.configuration.govsso.GovssoIdTokenDecoderFactory;
import ee.ria.govsso.client.configuration.govsso.GovssoLogoutTokenDecoderFactory;
import ee.ria.govsso.client.configuration.govsso.GovssoProperties;
import ee.ria.govsso.client.filter.OidcBackChannelLogoutFilter;
import ee.ria.govsso.client.filter.OidcRefreshTokenFilter;
import ee.ria.govsso.client.filter.OidcSessionExpirationFilter;
import ee.ria.govsso.client.oauth2.CustomAuthorizationRequestResolver;
import ee.ria.govsso.client.oauth2.CustomOidcClientInitiatedLogoutSuccessHandler;
import ee.ria.govsso.client.oauth2.LocalePassingLogoutHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.DefaultAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.header.HeaderWriter;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.session.ConcurrentSessionFilter;
import org.springframework.security.web.session.SessionManagementFilter;
import org.springframework.web.client.RestOperations;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static ee.ria.govsso.client.configuration.CookieConfiguration.COOKIE_NAME_XSRF_TOKEN;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN;
import static org.springframework.http.HttpHeaders.ORIGIN;

@Slf4j
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfiguration {

    private static final List<String> SESSION_UPDATE_CORS_ALLOWED_ENDPOINTS =
            Arrays.asList("/login/oauth2/code/govsso", "/dashboard");

    private final ClientRegistrationRepository clientRegistrationRepository;
    private final OAuth2AuthorizedClientService oAuth2AuthorizedClientService;

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            GovssoProperties govssoProperties,
            @Qualifier("govssoRestTemplate") RestOperations govssoRestOperations,
            SessionRegistry sessionRegistry,
            GovssoIdTokenDecoderFactory idTokenDecoderFactory,
            OAuth2UserService<OidcUserRequest, OidcUser> userService,
            Clock clock) throws Exception {
        // @formatter:off
        http
                .requestCache()
                    .requestCache(httpSessionRequestCache())
                    .and()
                .authorizeHttpRequests()
                    .antMatchers(
                            "/", "/assets/*", "/scripts/*", "/actuator/**")
                        .permitAll()
                    .requestMatchers(OidcBackChannelLogoutFilter.REQUEST_MATCHER)
                        .permitAll()
                    .anyRequest()
                        .authenticated()
                    .and()
                /*
                    Using custom strategy since default one creates new CSRF token for each authentication,
                    but CSRF token should not change during authentication for GovSSO session update.
                    CSRF can be disabled if application does not manage its own session and cookies.
                 */
                .csrf()
                    .ignoringRequestMatchers(OidcBackChannelLogoutFilter.REQUEST_MATCHER)
                    .csrfTokenRepository(csrfTokenRepository())
                    .and()
                .headers()
                    .xssProtection().disable()
                    .frameOptions().deny()
                    .contentSecurityPolicy(SecurityConstants.CONTENT_SECURITY_POLICY)
                        /*
                         *  Prevents browser from blocking functionality if views do not meet CSP requirements.
                         *  Problems are still displayed at browser console.
                         *  TODO: Remove this once given problems are fixed.
                         */
                        .reportOnly()
                        .and()
                    .httpStrictTransportSecurity()
                    .maxAgeInSeconds(Duration.ofDays(186).toSeconds())
                        .and()
                    .addHeaderWriter(corsHeaderWriter())
                        .and()
                .oauth2Login()
                    .userInfoEndpoint()
                        .oidcUserService(userService)
                        .and()
                    .authorizationEndpoint()
                        .authorizationRequestResolver(
                                new CustomAuthorizationRequestResolver(clientRegistrationRepository))
                        .and()
                    .tokenEndpoint()
                        .accessTokenResponseClient(createAccessTokenResponseClient(govssoRestOperations))
                        .and()
                    .defaultSuccessUrl("/dashboard")
                    .failureHandler(getAuthFailureHandler())
                    .and()
                .logout(logoutConfigurer -> {
                    logoutConfigurer.logoutUrl("/oauth/logout");
                    /*
                        Using custom handlers to pass ui_locales parameter to GovSSO logout flow.
                    */
                    logoutConfigurer
                            .logoutSuccessHandler(new CustomOidcClientInitiatedLogoutSuccessHandler(
                                    clientRegistrationRepository, govssoProperties.postLogoutRedirectUri()))
                            .getLogoutHandlers().add(0, new LocalePassingLogoutHandler());
                })
                .sessionManagement()
                     /*
                      * `.maximumSessions(...)` should always be configured as that makes sure a
                      * `ConcurrentSessionFilter` is created, which is required for our back-channel logout
                      * implementation to work. Without `ConcurrentSessionFilter`, expiring sessions from
                      * `SessionRegistry` would have no effect.
                      */
                    .maximumSessions(-1)
                    .expiredUrl("/?error=expired_session");
        // @formatter:on

        OidcBackChannelLogoutFilter oidcBackchannelLogoutFilter = OidcBackChannelLogoutFilter.builder()
                .clientRegistrationRepository(clientRegistrationRepository)
                .sessionRegistry(sessionRegistry)
                .logoutTokenDecoderFactory(new GovssoLogoutTokenDecoderFactory(govssoRestOperations))
                .build();
        http.addFilterAfter(oidcBackchannelLogoutFilter, SessionManagementFilter.class);

        OidcRefreshTokenFilter oidcRefreshTokenFilter = OidcRefreshTokenFilter.builder()
                .oAuth2AuthorizedClientService(oAuth2AuthorizedClientService)
                .restOperations(govssoRestOperations)
                .idTokenDecoderFactory(idTokenDecoderFactory)
                .userService(userService)
                .build();
        http.addFilterBefore(oidcRefreshTokenFilter, SessionManagementFilter.class);

        OidcSessionExpirationFilter oidcSessionExpirationFilter = OidcSessionExpirationFilter.builder()
                .clock(clock)
                .sessionRegistry(sessionRegistry)
                .build();
        http.addFilterBefore(oidcSessionExpirationFilter, ConcurrentSessionFilter.class);
        return http.build();
    }

    private static DefaultAuthorizationCodeTokenResponseClient createAccessTokenResponseClient(RestOperations govssoRestOperations) {
        DefaultAuthorizationCodeTokenResponseClient accessTokenResponseClient =
                new DefaultAuthorizationCodeTokenResponseClient();
        accessTokenResponseClient.setRestOperations(govssoRestOperations);
        return accessTokenResponseClient;
    }

    private HttpSessionRequestCache httpSessionRequestCache() {
        HttpSessionRequestCache httpSessionRequestCache = new HttpSessionRequestCache();
        // Disables session creation if session does not exist and any request returns 401 unauthorized error.
        httpSessionRequestCache.setCreateSessionAllowed(false);
        return httpSessionRequestCache;
    }

    private CsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName(COOKIE_NAME_XSRF_TOKEN);
        repository.setSecure(true);
        repository.setCookiePath("/");
        return repository;
    }

    private HeaderWriter corsHeaderWriter() {
        return (request, response) -> {
            // CORS is needed for automatic, in the background session extension.
            // But only for the endpoint that GovSSO redirects to after successful re-authentication process.
            // For that redirect Origin header is set to "null", since request comes from a "privacy-sensitive" context.
            // So setting CORS headers for given case only.
            // '/dashboard' must be included since the OAuth2 endpoint in turn redirects to dashboard.
            if (SESSION_UPDATE_CORS_ALLOWED_ENDPOINTS.contains(request.getRequestURI())) {
                String originHeader = request.getHeader(ORIGIN);
                if (originHeader != null && originHeader.equals("null")) {
                    response.addHeader(ACCESS_CONTROL_ALLOW_ORIGIN, "null");
                    response.addHeader(ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
                }
            }
        };
    }

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    public SimpleUrlAuthenticationFailureHandler getAuthFailureHandler() {
        return new SimpleUrlAuthenticationFailureHandler() {

            private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

            @Override
            public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
                    throws IOException {
                log.error("Authentication failed", exception);
                if (exception instanceof OAuth2AuthenticationException ex) {
                    redirectStrategy.sendRedirect(request, response, "/?error=" + ex.getError().getErrorCode());
                } else {
                    redirectStrategy.sendRedirect(request, response, "/?error=authentication_failure");
                }
            }
        };
    }
}
