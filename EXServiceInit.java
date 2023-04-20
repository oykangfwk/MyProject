package com.epoint.ztb.pre;

import org.apache.log4j.Logger;
import com.epoint.ztb.pre.Client.ZtbDataEXService;

/**
 * 交互服务初始化
 * @author djc
 *
 */
public class EXServiceInit{
	
	Logger log = Logger.getLogger(EXServiceInit.class);
	
	public void init() {
		try {
			new ZtbDataEXService().DoJob();
		} catch (Exception e) {
			log.error("数据交换服务启动初始化异常！", e);
		}
	}
}
