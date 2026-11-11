import urllib.request, urllib.error, http.cookiejar, json, uuid
base='http://127.0.0.1:8088'
jar=http.cookiejar.CookieJar();client=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
def call(path,body=None,csrf=True):
 headers={}
 if body is not None:
  headers['Content-Type']='application/json'
  if csrf:
   _,token=call('/api/auth/csrf');headers[token['header']]=token['token']
 req=urllib.request.Request(base+path,data=None if body is None else json.dumps(body).encode(),headers=headers)
 try:
  with client.open(req) as r:return r.status,json.load(r)
 except urllib.error.HTTPError as e:
  raw=e.read()
  try:data=json.loads(raw)
  except:data={}
  return e.code,data
account='qa-'+uuid.uuid4().hex+'@example.com'
print('TEST_ACCOUNT='+account)
assert call('/api/auth/me')[0]==401
payload={'username':'QA test','account':account,'password':'TestPass123!','confirmPassword':'TestPass123!'}
assert call('/api/auth/register',payload,False)[0]==403
assert call('/api/auth/register',dict(payload,password='weak',confirmPassword='weak'))[0]==400
assert call('/api/auth/register',payload)[0]==200
assert call('/api/auth/register',payload)[0]==409
assert call('/api/auth/login',dict(payload,password='wrong'))[0]==401
assert call('/api/auth/login',payload)[0]==200
assert call('/api/auth/me')[1]['account']==account
assert call('/api/auth/logout',{})[0]==200
assert call('/api/auth/me')[0]==401
assert client.open(base+'/DataLens-UI.html').status==200
print('PASS: MySQL registration, duplicate, validation, login, session, logout, CSRF and HTML')
