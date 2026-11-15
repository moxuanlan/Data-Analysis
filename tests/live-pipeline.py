"""Exercise the disposable test server and prepare a temporary MySQL round-trip check.
No real credentials, user accounts or production datasets are used.
"""
import csv, http.cookiejar, io, json, pathlib, secrets, time, urllib.request
base='http://127.0.0.1:18088'
client=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
def call(path,body=None,raw=False,headers=None):
    headers=dict(headers or {})
    if body is not None:
        csrf=call('/api/auth/csrf');headers[csrf['header']]=csrf['token']
        if not isinstance(body,bytes):body=json.dumps(body).encode();headers['Content-Type']='application/json'
    with client.open(urllib.request.Request(base+path,data=body,headers=headers),timeout=35) as response:
        return response.read() if raw else json.load(response)
account='pipeline-'+secrets.token_hex(5)+'@example.test';password='QA-'+secrets.token_hex(12)+'aA1!'
call('/api/auth/register',{'username':'Pipeline QA','account':account,'password':password,'confirmPassword':password})
call('/api/auth/login',{'account':account,'password':password})
text="中文 'quoted' \\ path\nsecond line"
stream=io.StringIO();writer=csv.writer(stream);writer.writerow(['id','description','optional']);writer.writerow(['001',text,'\\N']);writer.writerow(['002','','0'])
boundary='DataLens'+secrets.token_hex(8)
body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="roundtrip.csv"\r\nContent-Type: text/csv\r\n\r\n'+stream.getvalue()+f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="nullToken"\r\n\r\n\\N\r\n--{boundary}--\r\n').encode()
dataset=call('/api/data/upload',body,headers={'Content-Type':'multipart/form-data; boundary='+boundary});id=dataset['id']
call(f'/api/data/{id}/confirm',{})
page=call(f'/api/data/{id}');assert page['rows']==[['001',text,None],['002','','0']]
sql=call(f'/api/data/{id}/sql',{'table':'roundtrip','kind':'create_insert'},raw=True).decode()
name='datalens_validation_'+secrets.token_hex(6)
check=f"SELECT IF((SELECT COUNT(*) FROM roundtrip)=2 AND (SELECT HEX(description) FROM roundtrip WHERE id='001')='{text.encode().hex().upper()}' AND (SELECT optional IS NULL FROM roundtrip WHERE id='001')=1 AND (SELECT LENGTH(description) FROM roundtrip WHERE id='002')=0 AND (SELECT optional FROM roundtrip WHERE id='002')='0', 'PASS_MYSQL_ROUNDTRIP', 'FAIL_MYSQL_ROUNDTRIP');\n"
pathlib.Path('/tmp/datalens-mysql-check.sql').write_text(f'CREATE DATABASE `{name}` CHARACTER SET utf8mb4;\nUSE `{name}`;\n'+sql+'\n'+check+f'DROP DATABASE `{name}`;\n')
print('PASS: HTTP upload, preview, confirm and full SQL export; isolated MySQL verification script prepared')
job=call('/api/data/crawl',{'url':'https://www.tianqi.com/province/anhui/','target':100000})
for _ in range(45):
    state=next(x for x in call('/api/data/crawl') if x['id']==job['id'])
    if state['status'] not in ['QUEUED','RUNNING']:break
    time.sleep(1)
assert state['status']=='PARTIAL',state
assert 0<state['actual_count']<100000
weather=call('/api/data/'+str(state['dataset_id']))
assert weather['row_count']==state['actual_count']
assert all(row[4].startswith('https://www.tianqi.com/') for row in weather['rows'])
print('PASS: live weather source,',state['actual_count'],'actual rows, honest PARTIAL status and traceable source URLs')
