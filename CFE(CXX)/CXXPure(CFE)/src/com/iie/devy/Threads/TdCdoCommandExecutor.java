package com.iie.devy.Threads;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;

import org.apache.log4j.Logger;

import com.iie.devy.Cxxpure.CXX;
import com.iie.devy.Cxxpure.Default.UrlType;
import com.iie.devy.Cxxpure.Types.msgtype.NormalMessage;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

public class TdCdoCommandExecutor extends Thread
{
	private static final Logger logger=Logger.getLogger(TdCdoCommandExecutor.class);

	private int m_port;
	private CXX m_cxx;
	private boolean flag_stop=false;
	
	private boolean flag_debug=false;
	
	public TdCdoCommandExecutor(CXX cxx, int port)
	{
		m_cxx=cxx;
		m_port=port;
	}
	
	public void Shutdown()
	{
		flag_stop=true;
	}
	
	//处理新建code关系的Handler
	public class HNewCodePair implements HttpHandler
	{
		@Override
		public void handle(HttpExchange arg0) throws IOException {			
		//获取配置信息
			 BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(arg0.getRequestBody()));
			 String msgStr="";
			 String line="";
			 while( (line=bufferedReader.readLine()) != null)
			 {
				 msgStr += line;
			 }
			 
			 logger.info("[msg received]"+msgStr);
			 
			 NormalMessage msg = NormalMessage.FromJsonString(msgStr);
			 if(null==msg)
			 {
				 logger.error("unable to parse message:"+msgStr);
				 ReturnError(arg0);
				 return;
			 }
			 
			 //判断
			 if(msg.GetMsgType().equals(NormalMessage.REQ_NEW_HELPING_VIPS))
			 {
				String vipsName=m_cxx.StartHelpingWorkingVIPS(); 
				int port_ovs_vips=m_cxx.GetOvsVipsPortInfo(vipsName);
				//RespNewVips resp=new RespNewVips(port_ovs_vips);
				NormalMessage resp= new NormalMessage(NormalMessage.RSP_NEW_HELPING_VIPS,vipsName,""+port_ovs_vips);
				ReturnInfo(arg0, resp.ToJsonString());
			 }
			 else if(msg.GetMsgType().equals(NormalMessage.REQ_AVAIL_UFD_VIPS_PORT))
			 { 
			   	 int port_ovs_ufd=m_cxx.GetAvailOvsUfdPort();
			   	 NormalMessage tmsg=new NormalMessage(NormalMessage.RSP_AVAIL_UFD_VIPS_PORT,""+port_ovs_ufd);
			   	 ReturnInfo(arg0, tmsg.ToJsonString());
			 } 
			 else if(msg.GetMsgType().equals(NormalMessage.REQ_OVS_COMMAND))
			 {
				 int tunid=Integer.parseInt(msg.GetParam1());
				 int port=Integer.parseInt(msg.GetParam2());
			//1.执行ovs command
				 int ret=m_cxx.InitCodeOvsCommand(tunid,port);
			//2.告知ufd分流
				 m_cxx.InformUfdToOffload();
				 
				 if(ret==0)
				 {
					 //记录helping vips port信息
					 m_cxx.RecordForeignHelpingVipsPortInfo(port);
					 ReturnSuccess(arg0);
				 }
				 else
				 {
					 ReturnError(arg0);
				 }
			 }
			 else
			 {
				 ReturnUnknown(arg0);
			 }
			 			
		}
		
		
		
	}
	
	//处理释放Code的Handler
	public class HReleaseCodePair implements HttpHandler
	{
		public HReleaseCodePair()
		{
			
		}

		@Override
		public void handle(HttpExchange arg0) throws IOException {
			BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(arg0.getRequestBody()));
			 String msgStr="";
			 String line="";
			 while( (line=bufferedReader.readLine()) != null)
			 {
				 msgStr += line;
			 }
			 NormalMessage msg = NormalMessage.FromJsonString(msgStr);
			 if(null==msg)
			 {
				 logger.error("unable to parse message:"+msgStr);
				 ReturnError(arg0);
				 return;
			 }
			 
			 //请求为要求requester删除在ovs-ufd之间与code相关的端口
			 	//注：provider释放helping-vips的操作在TdLocalAdjust中完成
			 if(msg.GetMsgType().equals(NormalMessage.REQ_RELEASE_REQUESTERS_UFD_OVS_PORT))
			 {
				 //param1: tunid, param2:ovs-ufd port
				 int tunid=Integer.parseInt(msg.GetParam1());
				 int port_ufd_ovs_provider=Integer.parseInt(msg.GetParam2());
				 if(0==m_cxx.ReleaseCodeOvsCommand(tunid, port_ufd_ovs_provider))
				 {
					 //删除记录
					 m_cxx.RmRecordForeignHelpingVipsPortInfo(port_ufd_ovs_provider);
					 ReturnSuccess(arg0);
				 }
				 else
				 {
					 logger.error("error in release ufd-ovs port"+port_ufd_ovs_provider);
					 ReturnError(arg0);
				 }
			 }
			 else
			 {
				 ReturnUnknown(arg0);
			 }
			
		}
		
	}
	
	
	public void ReturnError(HttpExchange exchange) throws IOException
	{
		String response = UrlType.RESP_FAIL;
        exchange.sendResponseHeaders(200, 0);
        OutputStream os = exchange.getResponseBody();
        os.write(response.getBytes());
        os.close();
	}
	
	public void ReturnSuccess(HttpExchange exchange) throws IOException
	{
		String response = UrlType.RESP_SUCCESS;
        exchange.sendResponseHeaders(200, 0);
        OutputStream os = exchange.getResponseBody();
        os.write(response.getBytes());
        os.close();
	}
	
	public void ReturnUnknown(HttpExchange exchange) throws IOException
	{
		String response = UrlType.RESP_UNKNOWN;
        exchange.sendResponseHeaders(200, 0);
        OutputStream os = exchange.getResponseBody();
        os.write(response.getBytes());
        os.close();
	}
	
	public void ReturnInfo(HttpExchange exchange,String msg) throws IOException
	{
        exchange.sendResponseHeaders(200, 0);
        OutputStream os = exchange.getResponseBody();
        os.write(msg.getBytes());
        os.close();
	}
	
	public boolean GetDebugFlag()
	{
		return this.flag_debug;
	}

	
	@Override
	public void run()
	{
		logger.info("Thread TdCdoCommandExecutor launches");
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress(m_port), 0);
			
			server.createContext(UrlType.SUFFIX_CXX_CODE_PAIR_INIT, new HNewCodePair());
			server.createContext(UrlType.SUFFIX_CXX_CODE_PAIR_RELEASE, new HReleaseCodePair());
			server.setExecutor(null); // creates a default executor
			server.start();
			
			this.flag_debug=true;
			
			while(!this.flag_stop)
			{
				Thread.sleep(100);
			}
			
			server.stop(1);
			logger.info("Thread TdCdoCommandExecutor ends");
					
		} catch (IOException | InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
}
