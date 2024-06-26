package ee.ria.govsso.client.oauth2;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.logout.SimpleUrlLogoutSuccessHandler;
import org.springframework.security.web.util.UrlUtils;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static ee.ria.govsso.client.oauth2.LocalePassingLogoutHandler.UI_LOCALES_PARAMETER;

/**
 * A custom logout success handler for initiating OIDC logout with additional ui_locales parameter.
 *
 * @see org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler
 */
public class CustomOidcClientInitiatedLogoutSuccessHandler extends SimpleUrlLogoutSuccessHandler {
    private final ClientRegistrationRepository clientRegistrationRepository;
    private final String postLogoutRedirectUri;

    public CustomOidcClientInitiatedLogoutSuccessHandler(ClientRegistrationRepository clientRegistrationRepository, String postLogoutRedirectUri) {
        this.clientRegistrationRepository = clientRegistrationRepository;
        this.postLogoutRedirectUri = postLogoutRedirectUri;
    }

    @Override
    protected String determineTargetUrl(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        String targetUrl = null;
        if (authentication instanceof OAuth2AuthenticationToken && authentication.getPrincipal() instanceof OidcUser) {
            String registrationId = ((OAuth2AuthenticationToken) authentication).getAuthorizedClientRegistrationId();
            ClientRegistration clientRegistration = clientRegistrationRepository.findByRegistrationId(registrationId);
            URI endSessionEndpoint = endSessionEndpoint(clientRegistration);
            if (endSessionEndpoint != null) {
                String idToken = idToken(authentication);
                String postLogoutRedirectUri = postLogoutRedirectUri(request);
                targetUrl = endpointUri(request, endSessionEndpoint, idToken, postLogoutRedirectUri);
            }
        }
        return (targetUrl != null) ? targetUrl : super.determineTargetUrl(request, response);
    }

    private URI endSessionEndpoint(ClientRegistration clientRegistration) {
        if (clientRegistration != null) {
            ClientRegistration.ProviderDetails providerDetails = clientRegistration.getProviderDetails();
            Object endSessionEndpoint = providerDetails.getConfigurationMetadata().get("end_session_endpoint");
            if (endSessionEndpoint != null) {
                return URI.create(endSessionEndpoint.toString());
            }
        }
        return null;
    }

    private String idToken(Authentication authentication) {
        return ((OidcUser) authentication.getPrincipal()).getIdToken().getTokenValue();
    }

    private String postLogoutRedirectUri(HttpServletRequest request) {
        if (postLogoutRedirectUri == null) {
            return null;
        }
        UriComponents uriComponents = UriComponentsBuilder
                .fromHttpUrl(UrlUtils.buildFullRequestUrl(request))
                .replacePath(request.getContextPath())
                .replaceQuery(null)
                .fragment(null)
                .build();
        return UriComponentsBuilder.fromUriString(postLogoutRedirectUri)
                .buildAndExpand(Collections.singletonMap("baseUrl", uriComponents.toUriString()))
                .toUriString();
    }

    private String endpointUri(HttpServletRequest request, URI endSessionEndpoint, String idToken, String postLogoutRedirectUri) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUri(endSessionEndpoint);
        builder.queryParam("id_token_hint", idToken);
        builder.queryParam(UI_LOCALES_PARAMETER, request.getAttribute(UI_LOCALES_PARAMETER));
        if (postLogoutRedirectUri != null) {
            builder.queryParam("post_logout_redirect_uri", postLogoutRedirectUri);
        }
        return builder.encode(StandardCharsets.UTF_8)
                .build()
                .toUriString();
    }
}
