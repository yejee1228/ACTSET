package com.actset.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * 프런트(React) 빌드를 jar의 static/에 내장해 함께 배포한다(docs/09 "Spring Boot에 내장").
 * 화면 주소(/home, /projects/…)는 서버에 파일이 없으므로 index.html을 돌려줘 브라우저 라우터가 처리하게 한다 —
 * 그러지 않으면 새로고침·직접 접속 시 404가 난다. /api·/actuator는 대상이 아니다.
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String path, Resource location) throws IOException {
                        Resource r = location.createRelative(path);
                        if (r.exists() && r.isReadable()) return r;
                        if (path.startsWith("api/") || path.startsWith("actuator/")) return null;
                        Resource index = new ClassPathResource("/static/index.html");
                        return index.exists() ? index : null;
                    }
                });
    }
}
