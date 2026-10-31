package io.github.rnachoc.turno.core.api;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Applies authentication to every controller. Only Spring's own error page stays out, because the
 * container forwards failed requests to it without their original context.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SecurityProperties.class, RateLimitProperties.class})
class WebConfiguration implements WebMvcConfigurer {

    private final AuthenticationInterceptor authentication;

    WebConfiguration(AuthenticationInterceptor authentication) {
        this.authentication = authentication;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authentication).excludePathPatterns("/error");
    }
}
