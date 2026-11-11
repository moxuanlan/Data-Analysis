package dev.datalens;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.mock.web.*;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;
class AuthTest {
 @Test void registrationHashesPasswordAndRejectsWeakPassword(){
  JdbcTemplate db=mock(JdbcTemplate.class);var encoder=new BCryptPasswordEncoder(4);var auth=new Auth(db,encoder);
  assertThrows(ResponseStatusException.class,()->auth.register(new Auth.Credentials("test","test@example.com","weak","weak"),new MockHttpSession()));
  auth.register(new Auth.Credentials("test","test@example.com","Test12345","Test12345"),new MockHttpSession());
  var hash=ArgumentCaptor.forClass(String.class);
  verify(db).update(eq("INSERT INTO users(username,account,password_hash) VALUES(?,?,?)"),eq("test"),eq("test@example.com"),hash.capture());
  assertNotEquals("Test12345",hash.getValue());assertTrue(encoder.matches("Test12345",hash.getValue()));
 }
 @Test void sessionIsRequiredAndLogoutInvalidatesIt(){
  var auth=new Auth(mock(JdbcTemplate.class),new BCryptPasswordEncoder(4));var request=new MockHttpServletRequest();
  assertThrows(ResponseStatusException.class,()->auth.me(request));
  var user=new Auth.User(1,"test","test@example.com");request.getSession().setAttribute("user",user);assertEquals(user,auth.me(request));
  auth.logout(request);assertThrows(ResponseStatusException.class,()->auth.me(request));
 }
}
