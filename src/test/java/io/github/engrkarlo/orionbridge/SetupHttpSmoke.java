package io.github.engrkarlo.orionbridge;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.regex.Pattern;
final class SetupHttpSmoke {
    public static void main(String[] args)throws Exception{
        String token="d".repeat(64),claim="e".repeat(64);
        var setup=new SetupRequest();var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        try(var server=new PullHttp("127.0.0.1",0,token,null,null,setup)){
            String origin="http://127.0.0.1:"+server.port();
            String code=setup.open(System.currentTimeMillis());
            String body=String.join("\n",code,"123456789012345678","0123456789abcdef","123456789012345679","a".repeat(64),"b".repeat(64),"c".repeat(64),"CREDENTIALS",claim);
            check(post(client,origin+"/v3/pair","0".repeat(32),body).statusCode()==403);
            var response=post(client,origin+"/v3/pair",code,body);check(response.statusCode()==200);
            var m=Pattern.compile("\\\"id\\\":\\\"([a-f0-9]{16})\\\"").matcher(response.body());check(m.find());String id=m.group(1);
            check(!response.body().contains(token));check(post(client,origin+"/v3/claim",claim,id).statusCode()==403);
            var pending=setup.consume(id,System.currentTimeMillis());check(pending!=null);setup.approve(pending,token,System.currentTimeMillis());
            check(post(client,origin+"/v3/claim",token,id).statusCode()==403);
            var approved=post(client,origin+"/v3/claim",claim,id);check(approved.statusCode()==200&&approved.body().contains(token));
            check(post(client,origin+"/v3/pair",code,body).statusCode()==403);
        }
        System.out.println("SetupHttpSmoke passed");
    }
    static HttpResponse<String> post(HttpClient c,String uri,String secret,String body)throws Exception{return c.send(HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(3)).header("Authorization","Bearer "+secret).POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
    static void check(boolean ok){if(!ok)throw new AssertionError();}
}
