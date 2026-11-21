package dev.datalens;

import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import javax.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
@RequestMapping("/api/data/teams")
class Teams {
 final DatasetStore store;final Auth auth;
 Teams(DatasetStore store,Auth auth){this.store=store;this.auth=auth;}
 @GetMapping List<Map<String,Object>> list(HttpServletRequest r){return store.db.queryForList("SELECT t.id,t.name,t.owner_id FROM teams t JOIN team_members m ON t.id=m.team_id WHERE m.user_id=?",auth.me(r).id);}
 @GetMapping("/{team}") @Transactional(readOnly=true) Map<String,Object> details(@PathVariable long team,HttpServletRequest request){
  long user=auth.me(request).id;
  var teams=store.db.queryForList("SELECT t.name,u.username AS administrator FROM teams t JOIN users u ON u.id=t.owner_id JOIN team_members m ON m.team_id=t.id WHERE t.id=? AND m.user_id=?",team,user);
  if(teams.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"团队不存在或你不是该团队成员");
  var details=teams.get(0);
  details.put("members",store.db.queryForList("SELECT u.username FROM team_members m JOIN users u ON u.id=m.user_id WHERE m.team_id=? ORDER BY u.id",String.class,team));
  details.put("datasets",store.db.queryForList("SELECT d.id,d.name,d.row_count FROM dataset_shares s JOIN datasets d ON d.id=s.dataset_id WHERE s.team_id=? AND d.status='READY' ORDER BY d.id DESC",team));
  return details;
 }
 @PostMapping @Transactional Map<String,Long> create(@RequestBody Map<String,String> body,HttpServletRequest r){
  long owner=auth.me(r).id;String name=body.get("name");if(name==null||name.isBlank()||name.length()>100)throw DatasetStore.bad("团队名称须为 1–100 字符");
  var key=new GeneratedKeyHolder();store.db.update(c->{var p=c.prepareStatement("INSERT INTO teams(owner_id,name) VALUES(?,?)",new String[]{"id"});p.setLong(1,owner);p.setString(2,name);return p;},key);long id=Objects.requireNonNull(key.getKey()).longValue();store.db.update("INSERT INTO team_members(team_id,user_id) VALUES(?,?)",id,owner);return Map.of("id",id);
 }
 void owner(long team,long user){if(store.db.queryForObject("SELECT COUNT(*) FROM teams WHERE id=? AND owner_id=?",Integer.class,team,user)==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"团队不存在或无管理权限");}
 @PostMapping("/{team}/members") Map<String,String> add(@PathVariable long team,@RequestBody Map<String,String> body,HttpServletRequest r){
  owner(team,auth.me(r).id);String account=auth.account(body.get("account"));var users=store.db.queryForList("SELECT id FROM users WHERE LOWER(TRIM(account))=?",account);if(users.isEmpty())throw DatasetStore.bad("该账号尚未注册");if(users.size()>1)throw DatasetStore.bad("账号匹配不唯一，请联系管理员核对注册数据");long id=((Number)users.get(0).get("id")).longValue();
  if(id==auth.me(r).id)throw DatasetStore.bad("主管理员无需添加为成员");
  try{store.db.update("INSERT INTO team_members(team_id,user_id) VALUES(?,?)",team,id);}catch(org.springframework.dao.DuplicateKeyException ignored){}return Map.of("message","成员已加入，只能读取共享数据");
 }
 @GetMapping("/{team}/members") List<Map<String,Object>> members(@PathVariable long team,HttpServletRequest r){owner(team,auth.me(r).id);return store.db.queryForList("SELECT u.id,u.username,u.account FROM users u JOIN team_members m ON u.id=m.user_id WHERE m.team_id=? AND u.id<>(SELECT owner_id FROM teams WHERE id=?)",team,team);}
 @DeleteMapping("/{team}/members/{member}") Map<String,String> remove(@PathVariable long team,@PathVariable long member,HttpServletRequest r){long user=auth.me(r).id;owner(team,user);if(member==user)throw DatasetStore.bad("不能移除团队创建者");store.db.update("DELETE FROM team_members WHERE team_id=? AND user_id=?",team,member);return Map.of("message","成员访问权限已撤销");}
 @PostMapping("/{team}/datasets/{dataset}") Map<String,String> share(@PathVariable long team,@PathVariable long dataset,HttpServletRequest r){long user=auth.me(r).id;store.owned(dataset,user);if(store.db.queryForObject("SELECT COUNT(*) FROM team_members WHERE team_id=? AND user_id=?",Integer.class,team,user)==0)throw DatasetStore.bad("你不是该团队成员");try{store.db.update("INSERT INTO dataset_shares(dataset_id,team_id) VALUES(?,?)",dataset,team);}catch(org.springframework.dao.DuplicateKeyException ignored){}return Map.of("message","已只读共享；后续新版本需单独共享");}
 @DeleteMapping("/{team}/datasets/{dataset}") Map<String,String> unshare(@PathVariable long team,@PathVariable long dataset,HttpServletRequest r){store.owned(dataset,auth.me(r).id);store.db.update("DELETE FROM dataset_shares WHERE dataset_id=? AND team_id=?",dataset,team);return Map.of("message","共享已撤销");}
}
