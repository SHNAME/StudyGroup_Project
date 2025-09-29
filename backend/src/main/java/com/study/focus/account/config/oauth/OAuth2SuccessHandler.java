package com.study.focus.account.config.oauth;

import com.study.focus.account.domain.Provider;
import com.study.focus.account.dto.LoginResponse;
import com.study.focus.account.service.AccountService;
import com.study.focus.common.exception.BusinessException;
import com.study.focus.common.exception.UserErrorCode;
import com.study.focus.common.util.CookieUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

@RequiredArgsConstructor
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    public static final String REFRESH_TOKEN_COOKIE_NAME = "refresh_token";
    public static final Duration REFRESH_TOKEN_DURATION = Duration.ofDays(14);
    public static final String REDIRECT_PATH = "http://3.39.81.234:8080/home";
    // 프론트 배포 완료시 변경 + 환경변수 설정
    // public static final String REDIRECT_PATH = System.getenv("FRONTEND_REDIRECT_URL");

    private final AccountService accountService;
    private final OAuth2AuthorizationRequestBasedOnCookieRepository authorizationRequestRepository;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        String registrationId = ((OAuth2AuthenticationToken) authentication).getAuthorizedClientRegistrationId();
        Provider provider = Provider.valueOf(registrationId.toUpperCase());

        String providerUserId = extractProviderUserId(provider, oAuth2User.getAttributes());

        LoginResponse loginResponse = accountService.oauthLogin(provider, providerUserId);

        addRefreshTokenToCookie(request, response, loginResponse.getRefreshToken());

        String targetUrl = getTargetUrl(loginResponse.getAccessToken());

        clearAuthenticationAttributes(request, response);
        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }

    private String extractProviderUserId(Provider provider, Map<String, Object> attributes) {
        return switch (provider) {
            case GOOGLE -> (String) attributes.get("sub");
            case KAKAO -> String.valueOf(attributes.get("id"));
            case NAVER -> ((Map<String, Object>) attributes.get("response")).get("id").toString();
            default -> throw new BusinessException(UserErrorCode.UNSUPPORTED_PROVIDER);
        };
    }

    private void addRefreshTokenToCookie(HttpServletRequest request, HttpServletResponse response, String refreshToken) {
        int cookieMaxAge = (int) REFRESH_TOKEN_DURATION.toSeconds();
        CookieUtil.deleteCookie(request, response, REFRESH_TOKEN_COOKIE_NAME);
        CookieUtil.addCookie(response, REFRESH_TOKEN_COOKIE_NAME, refreshToken, cookieMaxAge);
    }

    private void clearAuthenticationAttributes(HttpServletRequest request, HttpServletResponse response) {
        super.clearAuthenticationAttributes(request);
        authorizationRequestRepository.removeAuthorizationRequestCookies(request, response);
    }

    private String getTargetUrl(String token) {
        return UriComponentsBuilder.fromHttpUrl(REDIRECT_PATH)
                .queryParam("token", token)
                .build()
                .toUriString();
    }
}
