package dev.datalens;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
@Configuration
class Security {
 @Bean BCryptPasswordEncoder encoder(){return new BCryptPasswordEncoder(12);}
 @Bean SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
  return http.authorizeHttpRequests(a->a.requestMatchers("/api/auth/**","/DataLens-UI.html","/DataLens-workflow.html","/error").permitAll().anyRequest().denyAll())
   .formLogin(f->f.disable()).httpBasic(b->b.disable()).logout(l->l.disable())
   .build();
 }
}
