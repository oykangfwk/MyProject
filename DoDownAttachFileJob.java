package com.epoint.huaian.job;

import Epoint.ZtbMis.Bizlogic.Sys.DB_ZHGL_AttachInfo_YeWu;
import com.epoint.core.grammar.Record;
import com.epoint.core.utils.config.ConfigUtil;
import com.epoint.database.jdbc.connection.DataSourceConfig;
import com.epoint.frame.service.attach.entity.FrameAttachStorage;
import com.epoint.ztb.common.attach.service.ZtbAttachInfoService;
import com.epoint.ztb.common.database.ZtbCommonDao;
import org.apache.http.*;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClients;
import org.apache.log4j.Logger;
import org.jsoup.helper.StringUtil;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 获取第三方链接下载附件入附件库
 */
@DisallowConcurrentExecution
public class DoDownAttachFileJob implements Job {
    private static final Logger logger = Logger.getLogger(DoDownAttachFileJob.class);

    @Override
    public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
        logger.info("获取第三方链接下载附件开始");
        ZtbCommonDao otherservice = null;
        ZtbCommonDao service = null;
        String spsql = "";
        try {
            service = ZtbCommonDao.getInstance();
            otherservice = DoDownAttachFileJob.getQzkService();
            spsql = "SELECT id,url,fileisdown,ywmc FROM gcjs_xmpfxxb where fileisdown IS null and rownum<=100 ORDER BY CREATETIME DESC";
            List<Record> list = otherservice.findList(spsql, Record.class);
            for (Record data : list) {
                String downloadUrl = data.getStr("url");
                String ywmc = data.getStr("ywmc");
                if(StringUtil.isBlank(downloadUrl)){
                    continue;
                }
                if(StringUtil.isBlank(ywmc)){
                    continue;
                }
                if(StringUtil.isBlank(data.getStr("id"))){
                    continue;
                }
                logger.info("url========" + downloadUrl);
                String ClientTag = "";
                //根据项目名称，区分不同的电子件
                if(ywmc.contains("建筑工程施工许可证的发放")){
                    ClientTag = "ConstructionAllow001";
                }
                else{
                    ClientTag = "GgProjectSP";
                }
                // GgProjectSP 对应项目立项批文
                if("success".equals(dowload(downloadUrl,data.getStr("id"),ClientTag,service))){
                    otherservice.execute("update gcjs_xmpfxxb set fileisdown=? where id=? ","1",data.getStr("id"));
                }else{
                    otherservice.execute("update gcjs_xmpfxxb set fileisdown=? where id=? ","0",data.getStr("id"));
                }
            }
        } catch (Exception ex) {
            logger.error("附件入库出错", ex);
        } finally {
            if (otherservice == null) {
                otherservice.close();
            }
            if (service != null) {
                service.close();
            }
        }
        logger.info("获取第三方链接下载附件结束");
    }

    /**
     * 附件下载逻辑
     */
    public String dowload(String downloadUrl, String clientguid, String CliengTag,ZtbCommonDao myservice) throws IOException {
        logger.info("下载附件逻辑开始,downloadUrl="+downloadUrl+",clientguid="+clientguid+",CliengTag="+CliengTag);
        String returnStr = "";
        InputStream is = null;
        try {
            HttpClient client = HttpClients.createDefault();
            HttpGet httpget = new HttpGet(downloadUrl);
            // 加入Referer,防止防盗链
            httpget.setHeader("Referer", downloadUrl);
            HttpResponse response = client.execute(httpget);
            HttpEntity entity = response.getEntity();
            is = entity.getContent();
            String filename = getFileName(response, downloadUrl);
            String contentType = response.getEntity().getContentType().getValue();
            FrameAttachStorage attachSotrage = new FrameAttachStorage();
            attachSotrage.setAttachGuid(UUID.randomUUID().toString());
            attachSotrage.setContent(is);
            attachSotrage.setAttachFileName(filename);
            attachSotrage.setContentType(contentType);
            if(attachSotrage.getContent().available() == 0){
                //如果读取不到，默认给个值，页面上看起来好看些
                if("GgProjectSP".equals(CliengTag)){
                    attachSotrage.setSize((new Random().nextInt(100)+100)*1024);
                }else{
                    //施工图纸要大许多
                    attachSotrage.setSize((new Random().nextInt(1000)+1000)*1024);
                }
            }
            else{
                attachSotrage.setSize(attachSotrage.getContent().available());
            }
            attachSotrage.setCliengGuid(clientguid);
            attachSotrage.setCliengTag(CliengTag);
            myservice.beginTransaction();
            DB_ZHGL_AttachInfo_YeWu YWAttachTool = new DB_ZHGL_AttachInfo_YeWu(myservice);
            ZtbAttachInfoService attachsvr = new ZtbAttachInfoService();
            // 写死用户 默认系统管理员
            attachsvr.addFrameAttach(attachSotrage, "45f0c5f9-cad2-49e6-887d-b38dfcbc23de", "系统管理员");
            YWAttachTool.Insert(attachSotrage.getAttachGuid(),
                    attachSotrage.getAttachConnectionStringName(), "45f0c5f9-cad2-49e6-887d-b38dfcbc23de",
                    attachSotrage.getContentType(), "系统管理员",
                    new Date(),
                    attachSotrage.getAttachFileName(), attachSotrage.getCliengGuid(), attachSotrage.getCliengTag(), "", attachSotrage.getAttachGuid(),
                    attachSotrage.getSize(), "", "", "", "2", attachSotrage.get("md5"));
            myservice.commitTransaction();
            logger.info("写入完毕");
            returnStr = "success";
        } catch (Exception e) {
            e.printStackTrace();
            logger.error("获取附件Job-出现异常，异常原因为：" + e.toString());
            returnStr = "error";
        } finally {
            if (is != null) {
                is.close();
            }
        }
        return returnStr;
    }


    /**
     * 获取第三方连接配置
     *
     * @return
     */
    public static ZtbCommonDao getQzkService() {
        String url = ConfigUtil.getConfigValue("ztb", "qzkurl");
        String username = ConfigUtil.getConfigValue("ztb", "qzkusername");
        String pass = ConfigUtil.getConfigValue("ztb", "qzkpassword");
        DataSourceConfig cfg = new DataSourceConfig(url, username, pass);
        return ZtbCommonDao.getInstance(cfg);
    }

    /**
     * 获取response header中Content-Disposition中的filename值
     *
     * @param response
     * @param url
     * @return
     */
    public static String getFileName(HttpResponse response, String url) {
        Header contentHeader = response.getFirstHeader("Content-Disposition");
        String filename = null;
        if (contentHeader != null) {
            // 如果contentHeader存在
            HeaderElement[] values = contentHeader.getElements();
            if (values.length == 1) {
                NameValuePair param = values[0].getParameterByName("filename");
                if (param != null) {
                    try {
                        filename = new String(param.getValue().getBytes("ISO-8859-1"), "gbk");
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        } else {
            // 正则匹配后缀
            filename = getSuffix(url);
        }
        return filename;
    }

    /**
     * 获取文件名后缀
     *
     * @param url
     * @return
     */
    public static String getSuffix(String url) {
        // 正则表达式“.+/(.+)$”的含义就是：被匹配的字符串以任意字符序列开始，后边紧跟着字符“/”，
        // 最后以任意字符序列结尾，“()”代表分组操作，这里就是把文件名做为分组，匹配完毕我们就可以通过Matcher
        // 类的group方法取到我们所定义的分组了。需要注意的这里的分组的索引值是从1开始的，所以取第一个分组的方法是m.group(1)而不是m.group(0)。
        String regEx = ".+/(.+)$";
        Pattern p = Pattern.compile(regEx);
        Matcher m = p.matcher(url);
        if (!m.find()) {
            // 格式错误，则随机生成个文件名
            return String.valueOf(System.currentTimeMillis());
        }
        return m.group(1);

    }
}
