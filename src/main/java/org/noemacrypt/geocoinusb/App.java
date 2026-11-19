package org.noemacrypt.geocoinusb;

import com.google.gson.*;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.net.http.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class App {
    private static final int HEADER_BYTES=124, NONCE_OFFSET=116;
    private final AtomicBoolean stopped=new AtomicBoolean(true);
    private volatile Thread controller;
    private volatile long rate;
    private volatile long gpuRate;
    private final AtomicBoolean gpuCancelled=new AtomicBoolean(false);
    private Config cfg;
    private String password;
    private Path appDir;

    public static void main(String[] args) {
        try { new App().run(args); }
        catch(Exception e) { System.err.println("GeoCoin Portable Miner: "+e.getMessage()); System.exit(1); }
    }
    private void run(String[] args) throws Exception {
        Path loc=Path.of(App.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path jarDir=Files.isDirectory(loc)?loc:loc.getParent();
        appDir=jarDir;
        if("target".equalsIgnoreCase(jarDir.getFileName().toString()) && Files.exists(jarDir.getParent().resolve("config.example.properties")))
            appDir=jarDir.getParent();
        Path file=args.length==0?appDir.resolve("config.properties"):Path.of(args[0]).toAbsolutePath().normalize();
        if(!Files.isRegularFile(file)) {
            Path example=appDir.resolve("config.example.properties");
            if(Files.isRegularFile(example)) Files.copy(example,file);
            throw new IOException("Edit config.properties, then run again: "+file);
        }
        cfg=Config.load(file);
        Path passwordFile=appDir.resolve("rpc-miner.pass");
        if(!Files.exists(passwordFile)) {
            Files.createFile(passwordFile);
            throw new IOException("Created rpc-miner.pass. Put the miner-role RPC password on its first line, then run again: "+passwordFile);
        }
        password=Files.readAllLines(passwordFile,StandardCharsets.UTF_8).stream().findFirst().orElse("").trim();
        if(password.isBlank())throw new IOException("Put the miner-role RPC password on the first line of rpc-miner.pass: "+passwordFile);
        validate();
        Console console=System.console();
        if(console==null) throw new IOException("Run from a terminal to control mining.");
        System.out.println("Mining is stopped. Reward address: "+cfg.address+"; CPU workers: "+cfg.threads+"; CPU limit: "+cfg.percent+"%.");
        System.out.println(cfg.gpuEnabled?"OpenCL GPUs enabled; selected devices: "+cfg.gpuDevices+", GPU limit: "+cfg.gpuPercent+"%.":"OpenCL GPU disabled.");
        System.out.println("Commands: start, status, gpu devices, gpu status, stop, quit");
        Runtime.getRuntime().addShutdownHook(new Thread(this::stopMiner));
        Thread usbWatch=new Thread(()->{
            while(true){
                try{Thread.sleep(1500);}catch(InterruptedException e){return;}
                if(!Files.exists(appDir)){System.err.println("USB drive is no longer available; exiting.");stopped.set(true);System.exit(0);}
            }
        },"geocoin-usb-watch");
        usbWatch.setDaemon(true);usbWatch.start();
        String line;
        while((line=console.readLine("> "))!=null) switch(line.trim().toLowerCase(Locale.ROOT)) {
            case "start" -> start(console);
            case "status" -> System.out.println(isRunning()?"CPU: "+rate+" H/s; GPU: "+gpuRate+" H/s":"Mining stopped");
            case "gpu devices" -> showGpuDevices();
            case "gpu status" -> System.out.println(cfg.gpuEnabled?"OpenCL enabled; devices "+cfg.gpuDevices+"; aggregate GPU: "+gpuRate+" H/s":"OpenCL disabled in config.properties");
            case "stop" -> stopMiner(); case "quit","exit" -> { stopMiner(); return; }
            case "" -> { } default -> System.out.println("Commands: start, status, stop, quit");
        }
        stopMiner();
    }
    private void start(Console c) {
        if(isRunning()) { System.out.println("Already running."); return; }
        if(!"YES".equals(c.readLine("Start mining on this computer for %s? Type YES to confirm: ",cfg.address))) {
            System.out.println("Mining remains stopped."); return;
        }
        stopped.set(false); controller=new Thread(this::mine,"geocoin-miner-controller"); controller.setDaemon(true); controller.start();
    }
    private boolean isRunning(){Thread t=controller;return !stopped.get()&&t!=null&&t.isAlive();}
    private void stopMiner(){stopped.set(true);gpuCancelled.set(true);Thread t=controller;if(t!=null&&t!=Thread.currentThread()){t.interrupt();try{t.join(5000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}rate=0;gpuRate=0;System.out.println("Mining stopped.");}
    private void showGpuDevices(){
        try{List<OpenClGeoPow.Device> devices=OpenClGeoPow.devices();if(devices.isEmpty()){System.out.println("No OpenCL GPU devices found. Install/update a vendor GPU driver with OpenCL support.");return;}for(OpenClGeoPow.Device d:devices)System.out.println(d.id()+": "+d.name());System.out.println("Select one or more IDs with gpu.devices=0,1 in config.properties.");}
        catch(RuntimeException e){System.out.println("OpenCL scan failed: "+e.getMessage());}
    }
    private void mine(){
        try { while(!stopped.get()) {
            JsonObject req=new JsonObject();req.addProperty("address",cfg.address);JsonArray ps=new JsonArray();ps.add(req);
            JsonObject tpl=rpc("getblocktemplate",ps).getAsJsonObject();byte[] block=unhex(tpl.get("blockhex").getAsString());
            if(block.length<HEADER_BYTES)throw new IOException("Short block template");
            long height=tpl.get("height").getAsLong();Result found=search(block,tpl.get("bits").getAsInt());
            rate=found.cpuCount*1000/Math.max(1,found.ms);if(stopped.get())break;
            if(found.nonce==null)continue;ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN).putLong(NONCE_OFFSET,found.nonce);
            JsonArray submit=new JsonArray();submit.add(hex(block));JsonElement result=rpc("submitblock",submit);
            System.out.println(result.isJsonNull()?"Accepted block at height "+height:"Block not accepted: "+result);
        }} catch(InterruptedException e){Thread.currentThread().interrupt();}
        catch(Exception e){if(!stopped.get())System.err.println("Miner stopped: "+e.getMessage());}
        finally{stopped.set(true);rate=0;gpuRate=0;controller=null;}
    }
    private Result search(byte[] block,int bits)throws InterruptedException{
        BigInteger target=target(bits);AtomicBoolean active=new AtomicBoolean(true);AtomicReference<Long>winner=new AtomicReference<>();AtomicLong count=new AtomicLong();AtomicReference<String> gpuError=new AtomicReference<>();
        long begin=System.nanoTime(),end=begin+TimeUnit.SECONDS.toNanos(10);List<Thread> pool=new ArrayList<>();
        gpuCancelled.set(false);gpuRate=0;
        for(int id=0;id<cfg.threads;id++){final int wid=id;Thread th=new Thread(()->{
            byte[] b=block.clone();MessageDigest sha;try{sha=MessageDigest.getInstance("SHA-256");}catch(Exception e){active.set(false);return;}
            long nonce=Integer.toUnsignedLong(wid),n=0,window=System.nanoTime();
            while(active.get()&&!stopped.get()&&System.nanoTime()<end){ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putLong(NONCE_OFFSET,nonce);sha.update(b,0,HEADER_BYTES);byte[] d=sha.digest(sha.digest());n++;
                if(new BigInteger(1,d).compareTo(target)<=0){if(winner.compareAndSet(null,nonce))active.set(false);break;}nonce+=cfg.threads;
                if((n&2047)==0){count.addAndGet(2048);n=0;window=throttle(window,active);}}
            count.addAndGet(n);
        },"geocoin-pow-"+id);pool.add(th);th.setDaemon(true);th.start();}
        List<Thread> gpuWorkers=new ArrayList<>();
        if(cfg.gpuEnabled){
            AtomicLongArray deviceRates=new AtomicLongArray(cfg.gpuDevices.size());
            for(int ordinal=0;ordinal<cfg.gpuDevices.size();ordinal++){
                final int lane=ordinal,deviceId=cfg.gpuDevices.get(ordinal);
                Thread gpu=new Thread(()->{try{Long n=OpenClGeoPow.mine(block,target,deviceId,(1L<<62)+lane,cfg.gpuDevices.size(),active,gpuCancelled,()->cfg.gpuPercent,end,value->{deviceRates.set(lane,value);long sum=0;for(int i=0;i<deviceRates.length();i++)sum+=deviceRates.get(i);gpuRate=sum;});if(n!=null&&winner.compareAndSet(null,n))active.set(false);}
                    catch(IOException|RuntimeException e){gpuError.compareAndSet(null,"device "+deviceId+": "+e.getMessage());}catch(InterruptedException e){Thread.currentThread().interrupt();}},"geocoin-opencl-miner-"+deviceId);
                gpu.setDaemon(true);gpuWorkers.add(gpu);gpu.start();
            }
        }
        for(Thread th:pool)th.join();for(Thread gpu:gpuWorkers)gpu.join();
        if(gpuError.get()!=null&&!stopped.get())System.err.println("OpenCL GPU unavailable; CPU mining continues: "+gpuError.get());
        return new Result(winner.get(),count.get(),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin));
    }
    private long throttle(long start,AtomicBoolean active){int p=cfg.percent;long elapsed=System.nanoTime()-start;if(p==0)sleep(100_000_000L,active);else if(p<100&&elapsed>=20_000_000L)sleep(elapsed*(100-p)/p,active);return System.nanoTime();}
    private void sleep(long ns,AtomicBoolean active){long end=System.nanoTime()+ns;while(active.get()&&!stopped.get()&&System.nanoTime()<end)try{TimeUnit.NANOSECONDS.sleep(Math.min(100_000_000L,end-System.nanoTime()));}catch(InterruptedException e){Thread.currentThread().interrupt();active.set(false);}}
    private JsonElement rpc(String method,JsonElement params)throws Exception{
        JsonObject body=new JsonObject();body.addProperty("jsonrpc","2.0");body.addProperty("id",1);body.addProperty("method",method);body.add("params",params);
        String auth=Base64.getEncoder().encodeToString((cfg.user+":"+password).getBytes(StandardCharsets.UTF_8));
        HttpRequest r=HttpRequest.newBuilder(cfg.url).timeout(Duration.ofSeconds(30)).header("Authorization","Basic "+auth).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<String> resp=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build().send(r,HttpResponse.BodyHandlers.ofString());
        if(resp.statusCode()<200||resp.statusCode()>=300)throw new IOException("RPC HTTP status "+resp.statusCode());JsonObject j=JsonParser.parseString(resp.body()).getAsJsonObject();
        if(j.has("error")&&!j.get("error").isJsonNull())throw new IOException(method+" RPC error: "+j.get("error"));if(!j.has("result"))throw new IOException("Missing RPC result");return j.get("result");
    }
    private void validate()throws Exception{String scheme=cfg.url.getScheme(),host=cfg.url.getHost();if(host==null||cfg.url.getUserInfo()!=null||cfg.url.getFragment()!=null)throw new IllegalArgumentException("Invalid node.url");
        boolean loop="localhost".equalsIgnoreCase(host)||InetAddress.getByName(host).isLoopbackAddress();if(!"https".equalsIgnoreCase(scheme)&&!("http".equalsIgnoreCase(scheme)&&loop))throw new IllegalArgumentException("Remote RPC requires HTTPS; HTTP only works on loopback");
        if(cfg.user.isBlank()||cfg.address.isBlank())throw new IllegalArgumentException("Set node.username and mining.address");if(cfg.threads<1||cfg.threads>256||cfg.percent<0||cfg.percent>100||cfg.gpuPercent<0||cfg.gpuPercent>100)throw new IllegalArgumentException("threads: 1-256, CPU/GPU limits: 0-100");if(cfg.gpuEnabled&&cfg.gpuDevices.isEmpty())throw new IllegalArgumentException("Set gpu.devices after checking `gpu devices`.");if(cfg.gpuDevices.stream().anyMatch(id->id<0))throw new IllegalArgumentException("GPU device IDs must be non-negative.");}
    private static BigInteger target(int c){long b=Integer.toUnsignedLong(c);int e=(int)(b>>>24);long m=b&0x7fffffL;if((b&0x800000L)!=0||m==0)throw new IllegalArgumentException("Invalid compact target");BigInteger x=BigInteger.valueOf(m);return e<=3?x.shiftRight(8*(3-e)):x.shiftLeft(8*(e-3));}
    private static byte[] unhex(String s){if((s.length()&1)!=0)throw new IllegalArgumentException("Invalid blockhex");byte[]b=new byte[s.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return b;}
    private static String hex(byte[]b){StringBuilder s=new StringBuilder(b.length*2);for(byte x:b)s.append(String.format(Locale.ROOT,"%02x",x&255));return s.toString();}
    private record Result(Long nonce,long cpuCount,long ms){}
    private record Config(URI url,String user,String address,int threads,int percent,boolean gpuEnabled,List<Integer> gpuDevices,int gpuPercent){
        static Config load(Path f)throws IOException{Properties p=new Properties();try(Reader r=Files.newBufferedReader(f,StandardCharsets.UTF_8)){p.load(r);}String selected=p.getProperty("gpu.devices","").trim();List<Integer> ids;if(!selected.isEmpty()){ids=Arrays.stream(selected.split(",")).map(String::trim).filter(s->!s.isEmpty()).map(Integer::parseInt).distinct().toList();}else{int legacy=Integer.parseInt(p.getProperty("gpu.device","-1"));ids=legacy<0?List.of():List.of(legacy);}return new Config(URI.create(p.getProperty("node.url","")),p.getProperty("node.username","").trim(),p.getProperty("mining.address","").trim(),Integer.parseInt(p.getProperty("mining.threads","1")),Integer.parseInt(p.getProperty("limits.cpu-percent","35")),Boolean.parseBoolean(p.getProperty("gpu.enabled","false")),ids,Integer.parseInt(p.getProperty("limits.gpu-percent","100")));}
    }
}
