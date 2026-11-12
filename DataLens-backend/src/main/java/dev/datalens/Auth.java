package dev.datalens;
import java.util.*;
import java.nio.charset.StandardCharsets;
import javax.servlet.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
@RestController
@RequestMapping("/api/auth")
class Auth {
 private final JdbcTemplate db; private final BCryptPasswordEncoder encoder;
 Auth(JdbcTemplate db,BCryptPasswordEncoder encoder){this.db=db;this.encoder=encoder;}
 static class Credentials {
  public String username,account,password,confirmPassword;
  public Credentials(){}
  Credentials(String username,String account,String password,String confirmPassword){this.username=username;this.account=account;this.password=password;this.confirmPassword=confirmPassword;}
  String username(){return username;} String account(){return account;} String password(){return password;} String confirmPassword(){return confirmPassword;}
 }
 static class User implements java.io.Serializable {
  public final long id; public final String name,account;
  User(long id,String name,String account){this.id=id;this.name=name;this.account=account;}
 }
 @GetMapping("/csrf") Map<String,String> csrf(CsrfToken token){return Map.of("token",token.getToken(),"header",token.getHeaderName());}
 String account(String value){
  String a=value==null?"":value.trim().toLowerCase(Locale.ROOT);
  if(a.length()>254 || !a.matches("(?:1[3-9]\\d{9}|[^\\s@]+@[^\\s@]+\\.[^\\s@]+)"))throw error(400,"请输入有效的手机号或邮箱");
  return a;
 }
 @PostMapping("/register") Map<String,String> register(@RequestBody Credentials c,HttpSession session){
  throttle(session);
  String a=account(c.account());String name=c.username()==null?"":c.username().trim();String p=c.password();
  if(name.isEmpty()||name.length()>40)throw error(400,"用户名需为 1–40 个字符");
  if(p==null||p.length()<8||p.getBytes(StandardCharsets.UTF_8).length>72||!p.matches("(?s).*[a-z].*")||!p.matches("(?s).*[A-Z].*")||!p.matches("(?s).*[0-9].*"))throw error(400,"密码至少 8 位，包含大小写字母和数字，最多 72 字节");
  if(!p.equals(c.confirmPassword()))throw error(400,"两次输入的密码不一致");
  try{db.update("INSERT INTO users(username,account,password_hash) VALUES(?,?,?)",name,a,encoder.encode(p));}
  catch(DuplicateKeyException e){throw error(409,"该账号已注册，请登录");}
  return Map.of("message","注册成功，请登录");
 }
 @PostMapping("/login") User login(@RequestBody Credentials c,HttpServletRequest request){
  throttle(request.getSession());String a=account(c.account());
  if(c.password()==null||c.password().getBytes(StandardCharsets.UTF_8).length>72)throw error(401,"账号或密码错误");
  var found=db.queryForList("SELECT id,username,account,password_hash FROM users WHERE account=?",a);
  if(found.isEmpty())throw error(401,"请先完成注册再进行登录");
  var row=found.get(0);if(!encoder.matches(c.password(),(String)row.get("password_hash")))throw error(401,"账号或密码错误");
  request.changeSessionId();User u=new User(((Number)row.get("id")).longValue(),(String)row.get("username"),a);
  request.getSession().setAttribute("user",u);return u;
 }
 @GetMapping("/me") User me(HttpServletRequest request){HttpSession s=request.getSession(false);if(s==null||!(s.getAttribute("user") instanceof User))throw error(401,"请先登录");return (User)s.getAttribute("user");}
 @PostMapping("/logout") Map<String,String> logout(HttpServletRequest request){var s=request.getSession(false);if(s!=null)s.invalidate();return Map.of("message","已退出登录");}
 // shortcut: Session-local limit for the initial local-only release; use shared IP/account limits before public deployment.
 void throttle(HttpSession session){long now=System.currentTimeMillis();Long start=(Long)session.getAttribute("attemptStart");Integer count=(Integer)session.getAttribute("attemptCount");if(start==null||now-start>60000){start=now;count=0;}if(count>=10)throw error(429,"操作频繁，请一分钟后再试");session.setAttribute("attemptStart",start);session.setAttribute("attemptCount",count+1);}
 ResponseStatusException error(int status,String message){return new ResponseStatusException(HttpStatus.valueOf(status),message);}
 @ExceptionHandler(ResponseStatusException.class) org.springframework.http.ResponseEntity<Map<String,String>> errors(ResponseStatusException e){return org.springframework.http.ResponseEntity.status(e.getStatus()).body(Map.of("message",e.getReason()));}
}

