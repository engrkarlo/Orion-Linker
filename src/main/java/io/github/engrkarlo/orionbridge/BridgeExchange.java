package io.github.engrkarlo.orionbridge;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.util.HashMap;
import java.util.Map;

/** Bounded request/response adapter: both transports use the same signed route handlers. */
final class BridgeExchange extends HttpExchange {
    static final int MAX_RESPONSE=1024*1024;
    private final URI uri;private final String method;private final Headers headers,response=new Headers();
    private final Map<String,Object> attributes=new HashMap<>();
    private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
    private InputStream input;
    private final OutputStream output=new OutputStream(){
        @Override public void write(int value)throws IOException{if(bytes.size()>=MAX_RESPONSE)throw new IOException("response limit");bytes.write(value);}
        @Override public void write(byte[] value,int offset,int length)throws IOException{if(length>MAX_RESPONSE-bytes.size())throw new IOException("response limit");bytes.write(value,offset,length);}
    };
    private int status=500;
    BridgeExchange(String method,String path,Headers headers,byte[] body){this.method=method;this.uri=URI.create(path);this.headers=headers;this.input=new ByteArrayInputStream(body);}
    byte[] body(){return bytes.toByteArray();}
    @Override public Headers getRequestHeaders(){return headers;}
    @Override public Headers getResponseHeaders(){return response;}
    @Override public URI getRequestURI(){return uri;}
    @Override public String getRequestMethod(){return method;}
    @Override public HttpContext getHttpContext(){return null;}
    @Override public void close(){}
    @Override public InputStream getRequestBody(){return input;}
    @Override public OutputStream getResponseBody(){return output;}
    @Override public void sendResponseHeaders(int code,long length)throws IOException{if(length>MAX_RESPONSE)throw new IOException("response limit");status=code;}
    @Override public InetSocketAddress getRemoteAddress(){return null;}
    @Override public InetSocketAddress getLocalAddress(){return null;}
    @Override public int getResponseCode(){return status;}
    @Override public String getProtocol(){return "HTTP/1.1";}
    @Override public Object getAttribute(String name){return attributes.get(name);}
    @Override public void setAttribute(String name,Object value){attributes.put(name,value);}
    @Override public void setStreams(InputStream in,OutputStream out){throw new UnsupportedOperationException();}
    @Override public HttpPrincipal getPrincipal(){return null;}
}
