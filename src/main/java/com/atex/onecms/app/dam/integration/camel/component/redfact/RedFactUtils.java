package com.atex.onecms.app.dam.integration.camel.component.redfact;

import com.atex.onecms.app.dam.standard.aspects.OneArticleBean;
import com.atex.onecms.app.dam.standard.aspects.OneImageBean;
import com.atex.onecms.content.*;
import com.atex.onecms.content.aspects.Aspect;
import com.atex.onecms.content.callback.CallbackException;
import com.atex.onecms.content.metadata.MetadataInfo;
import com.atex.onecms.image.*;
import com.atex.onecms.ws.image.ImageServiceConfigurationProvider;
import com.atex.onecms.ws.image.ImageServiceUrlBuilder;
import com.atex.plugins.structured.text.StructuredText;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jcraft.jsch.*;
import com.polopoly.cm.client.CMException;
import com.polopoly.cm.client.CmClient;
import com.polopoly.metadata.Dimension;
import com.polopoly.metadata.Entity;
import org.apache.commons.httpclient.HttpClientError;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class RedFactUtils {

    private static Logger log = LoggerFactory.getLogger(RedFactUtils.class);

    public static final String REDFACT_ARTICLE_SEGMENT = "ar";
    public static final String REDFACT_IMAGE_SEGMENT = "img";

    public RedFactFormArticle convert(String imageServiceUrl, RedfactConfig redFactConfig, CmClient cmClient, ContentManager contentManager, final ContentResult<OneArticleBean> source)
            throws CallbackException {

        final OneArticleBean damArticle = source.getContent().getContentData();

        List<NameValuePair> formArticleParams = convertArticleToForm(source, damArticle);
        List<ContentId> images = damArticle.getImages();
        List<RedFactFormImage> redFactFormImages = new ArrayList<>();
        String secret;
        try {
            secret = new ImageServiceConfigurationProvider(
              cmClient.getPolicyCMServer()).getImageServiceConfiguration().getSecret();
        } catch (CMException e) {
            throw new RuntimeException("Secret not found", e);
        }


        for (ContentId image : images) {
            ContentVersionId contentVersionId = contentManager.resolve(image, Subject.NOBODY_CALLER);
            ContentResult<OneImageBean> imageBean = contentManager.get(contentVersionId, OneImageBean.class, Subject.NOBODY_CALLER);
            OneImageBean damImage = imageBean.getContent().getContentData();

            // calculate url the redfact uses to get to the sftp store
            String redfactImageUrl = redFactConfig.getExternalImageStoreUrl() + "/" + contentVersionId.getContentId().getKey();
            redfactImageUrl = tidyUrl(redfactImageUrl);

            Content<OneImageBean> content = imageBean.getContent();
            Aspect imageEditAspect = content.getAspect("atex.ImageEditInfo");

            ImageEditInfoAspectBean imageEditInfo = null;
            if (imageEditAspect != null) {
                imageEditInfo = (ImageEditInfoAspectBean) imageEditAspect.getData();
            }

            ImageInfoAspectBean ii = (ImageInfoAspectBean) content.getAspect(ImageInfoAspectBean.ASPECT_NAME).getData();

            RedFactCropCoordinates coordinates = null;
            String imageFormat = redFactConfig.getImageFormat().replace(':','x');
            if (imageEditInfo != null) {
                CropInfo crop = imageEditInfo.getCrop(imageFormat);

                if (crop != null) {
                    Rectangle rect = crop.getCropRectangle();
                    coordinates = new RedFactCropCoordinates(rect.getX(),rect.getY(),rect.getX()+rect.getWidth(), rect.getY()+rect.getHeight());
                }
            }

            List<NameValuePair> params = convertImageToForm(redfactImageUrl, imageBean, damImage, coordinates);

            // calculate the url to the onecms store for the image
            ImageServiceUrlBuilder imageBuilder = new ImageServiceUrlBuilder(imageBean, secret);
            if (StringUtils.isNotEmpty(redFactConfig.getImageFormat())) imageBuilder.aspectRatio(AspectRatio.valueOf(redFactConfig.getImageFormat()));
            String imageUrl = imageServiceUrl+"/"+imageBuilder.buildUrl();
            imageUrl = tidyUrl(imageUrl);

            RedFactFormImage redFactFormImage = new RedFactFormImage(contentVersionId.getContentId().getKey(), imageUrl, params);
            redFactFormImages.add(redFactFormImage);
        }

        return new RedFactFormArticle(source.getContentId().getContentId().toString(), formArticleParams, redFactFormImages);
    }

    public List<NameValuePair> convertArticleToForm(ContentResult<OneArticleBean> source, OneArticleBean damArticle) {
        List<NameValuePair> params = new ArrayList<>();
        // fixed params
        MetadataInfo metadataInfo = (MetadataInfo)source.getContent().getAspect("atex.Metadata").getData();
        Dimension ressort1Dimension = metadataInfo.getMetadata().getDimensionById("dimension.ressort1");
        if (ressort1Dimension != null && ressort1Dimension.getEntities() != null && ressort1Dimension.getEntities().size() > 0) {
            Entity entity = ressort1Dimension.getEntities().get(0);
            params.add(new BasicNameValuePair("catchline_atex", entity.getName())); // fixed
        }
        params.add(new BasicNameValuePair("category", "2014")); // fixed
        params.add(new BasicNameValuePair("status", "pu_all#wo_0")); // fixed
        // end fixed

        // If the checkbox "Zahlungspflichtig" is checked, the value "is_paid_content" should be "1"
        // (and "0" or non-existent if not).
        params.add(new BasicNameValuePair("is_paid_content", damArticle.isPremiumContent() ? "1" : "0"));

        params.add(new BasicNameValuePair("name",getStructuredText(damArticle.getHeadline())));
        params.add(new BasicNameValuePair("editor_teaser",getStructuredText(damArticle.getLead())));
        params.add(new BasicNameValuePair("editor_text",getStructuredText(damArticle.getBody())));
        params.add(new BasicNameValuePair("author",damArticle.getAuthor()));
        params.add(new BasicNameValuePair("id_atex",source.getContentId().getContentId().getKey()));
        int priority = damArticle.getPriority();
        String topStory = "0";
        if (priority == 0)
            priority = calculatePriority(damArticle.getName(), damArticle.getWords());
        else {
            if (priority > 10) {
                priority = priority - 10;
                topStory = "1";
            }
        }
        params.add(new BasicNameValuePair("priority", Integer.toString(priority)));
        params.add(new BasicNameValuePair("topstory",topStory));
        com.atex.onecms.app.dam.types.TimeState t = damArticle.getTimeState();
        if (t != null) {
            Long offtime = t.getOfftime();
            Long ontime = t.getOntime();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            if (offtime != null && offtime != 0) {
                LocalDateTime dt = LocalDateTime.ofEpochSecond(offtime/1000,0,ZoneOffset.UTC);
                params.add(new BasicNameValuePair("valid_till", Long.toString(dt.toEpochSecond(ZoneOffset.UTC))));
            }
            if (ontime != null && ontime != 0) {
                LocalDateTime dt = LocalDateTime.ofEpochSecond(ontime/1000,0,ZoneOffset.UTC);
                params.add(new BasicNameValuePair("valid_from",Long.toString(dt.toEpochSecond(ZoneOffset.UTC))));
            }
        }
        params.add(new BasicNameValuePair("lastchgdate",getLastModifiedDate(damArticle)));
        return params;
    }

    public String getStructuredText(StructuredText str) {
        if (str != null)
            return str.getText();
        else
            return "";
    }

    public String getLastModifiedDate(OneArticleBean damArticle) {
        try {
            Method getLastAmendedTime = damArticle.getClass().getMethod("getLastAmendedTime");
            if (getLastAmendedTime == null) {
                log.warn("Unable to get getLastAmendedTime method, on article.  No modified time available");
                return "";
            }
            Object modificationdate = getLastAmendedTime.invoke(damArticle);
            if (modificationdate instanceof Long && ((Long)modificationdate) > 0) {
                LocalDateTime dt = LocalDateTime.ofEpochSecond((Long)modificationdate,0, ZoneOffset.UTC);
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-ddTHH:mm:ss");
                return formatter.format(dt);
            }
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            log.debug("unable to get modification date",e);
        }
        return "";
    }

    public int calculatePriority(String name, int wordCount) {
        if (name.startsWith("H4")) return 5;
        if (wordCount < 201) return 4;
        if (wordCount < 401) return 3;
        if (wordCount < 601) return 2;
        return 1;
    }

    public String truncateWords(final String value, int blankCount, final String suffix) {
        if (!Strings.isNullOrEmpty(value)) {
            final StringBuilder sb = new StringBuilder();
            int spaceCount = 0;
            for (final String text : Splitter.on(" ").split(value.trim())) {
                if (Strings.isNullOrEmpty(text)) {
                    sb.append(" ");
                    continue;
                }
                sb.append(text);
                if (spaceCount++ >= blankCount) {
                    break;
                }
                sb.append(" ");
            }
            String text = sb.toString().trim();
            if (text.length() < (value.trim()).length()) {
                text += suffix;
            }
            return text;
        }
        return value;
    }

    public List<NameValuePair> convertImageToForm(String url, ContentResult<OneImageBean> source, OneImageBean damImageBean, RedFactCropCoordinates coordinates) {
        List<NameValuePair> params = new ArrayList<>();
        // fixed params
        params.add(new BasicNameValuePair("category", "4")); // fixed
        // end fixed
        params.add(new BasicNameValuePair("name",damImageBean.getName()));
        // Send caption to RedFact, if existent
        String caption = damImageBean.getCaption();
        if(caption != null && caption.length() > 0)
            params.add(new BasicNameValuePair("editor_text", damImageBean.getCaption()));

        params.add(new BasicNameValuePair("url_bild_org",url));
        if (coordinates != null) {
            params.add(new BasicNameValuePair("x1", Integer.toString(coordinates.getX1())));
            params.add(new BasicNameValuePair("y1", Integer.toString(coordinates.getY1())));
            params.add(new BasicNameValuePair("x2", Integer.toString(coordinates.getX2())));
            params.add(new BasicNameValuePair("y2", Integer.toString(coordinates.getY2())));
        }

        return params;
    }

    public void sendUrlToSftp(String srcUrl, String privateSshKeyPath, String destFilename, String destUsername, String destHost, int destPort, String destPath) {

        JSch ssh = new JSch();
//        String privateSshKeyPath = "~/.ssh/id_rsa";

        Session session = null;
        Channel channel = null;
        try {
            ssh.addIdentity(privateSshKeyPath);
            session = ssh.getSession(destUsername,destHost,destPort);
            session.setConfig("StrictHostKeyChecking", "no");
            session.setConfig("PreferredAuthentications", "publickey");
            session.setTimeout(15000);
            session.connect();
            channel = session.openChannel("sftp");
            ChannelSftp sftpChannel = (ChannelSftp) channel;
            sftpChannel.connect();
            sftpChannel.cd(destPath);
            String url = tidyUrl(srcUrl);
            sftpChannel.put(new URL(url).openStream(), destFilename);
            sftpChannel.quit();
            session.disconnect();
        } catch (JSchException e) {
            log.error("Failed to connect to sftp server",e);
        } catch (SftpException e) {
            if (e.id == 4)
                log.error("Failed to change directory to "+destPath);
            else
                log.error("Failed to connect to sftp server",e);
        } catch (MalformedURLException e) {
            log.error("Failed to create url to sftp server",e);
        } catch (IOException e) {
            log.error("Failed to send file via sftp",e);
        } finally {
            if (channel != null) channel.disconnect();
            if (session != null) session.disconnect();
        }

    }

    public Pair<String,Integer> sendForm(final String url, final Collection<NameValuePair> params) throws IOException {
        if (log.isDebugEnabled()) {
            log.debug("Send To Redfact URL: "+url);
            for (NameValuePair param : params) {
                log.debug("Name: "+param.getName()+" Value: "+param.getValue());
            }
        }
        try (final OutputStream stream = new ByteArrayOutputStream()) {
            CloseableHttpClient httpclient = HttpClients.createDefault();
            HttpPost method = new HttpPost(url);
            method.setEntity(new UrlEncodedFormEntity(params, StandardCharsets.UTF_8));
            try (CloseableHttpResponse response = httpclient.execute(method)) {
                log.debug(response.getStatusLine().toString());
                HttpEntity responseEntity = response.getEntity();
                String left = EntityUtils.toString(responseEntity, StandardCharsets.UTF_8);
                String redFactId = "";
                try {
                    JsonObject jsonResult = new JsonParser().parse(left).getAsJsonObject();
                    redFactId = jsonResult.get("id").getAsString();
                } catch (Exception e) {};
                Integer right = response.getStatusLine().getStatusCode();
                Pair<String,Integer> result = new ImmutablePair<String,Integer>(redFactId, right);
                EntityUtils.consume(responseEntity);
                return result;
            }
        }
    }

    public Pair<String, Integer> sendArticleFormToRedFact(String apiUrl, final String contentIdString, final ContentResult<OneArticleBean> cr, final RedFactFormArticle redFactFormArticle) throws IOException, URISyntaxException, HttpClientError {

        try {
            URIBuilder builder = new URIBuilder(apiUrl);
            String url = builder.setPath(builder.getPath()+"/"+REDFACT_ARTICLE_SEGMENT).toString();
            Pair<String,Integer> httpResult = sendForm(url, redFactFormArticle.getFormArticle());
            log.debug("response: " + httpResult.getKey());
            if (httpResult.getValue() == 412) {
                log.debug("create failed, sending update");
                // either article failed or needs exists
                // try to update instead
                String updateUrl = url;
                if (!updateUrl.endsWith("/")) updateUrl += "/";
                updateUrl += contentIdString.substring("onecms:".length());
                log.debug("sending update url:"+updateUrl);
                httpResult = sendForm(updateUrl, redFactFormArticle.getFormArticle());
                log.debug("update response: " + httpResult.getKey());
            }
            log.debug("status = "+httpResult.getValue());
            if (httpResult.getValue() == 200) {
                log.debug("Article send successful, setting status");
            } else {
                throw new HttpClientError("Sending article to redfact failed status = "+httpResult.getValue());
            }
            return httpResult;
        } catch (URISyntaxException e) {
            log.error("Failed to parse redfact uri : "+apiUrl);
            throw e;
        } catch (HttpClientError e) {
            log.error(e.getMessage());
            throw e;
        }
    }

    public Pair<String, Integer> sendImageFormToRedFact(String apiUrl, RedFactFormImage redFactFormImage) throws IOException, URISyntaxException {

        URIBuilder builder = new URIBuilder(apiUrl);
        String url = builder.setPath(builder.getPath()+"/"+REDFACT_IMAGE_SEGMENT).toString();
        Pair<String,Integer> httpResult = sendForm(url, redFactFormImage.getFormImage());
        log.debug("response: " + httpResult.getKey());
        if (httpResult.getValue() == 412) {
            log.debug("create failed, sending update");
            // either article failed or needs exists
            // try to update instead
            String updateUrl = url;
            if (!updateUrl.endsWith("/")) updateUrl += "/";
            updateUrl += redFactFormImage.getContentIdString().substring("onecms:".length());
            log.debug("sending update url:"+updateUrl);
            httpResult = sendForm(updateUrl, redFactFormImage.getFormImage());
            log.debug("update response: " + httpResult.getKey());
        }
        log.debug("status = "+httpResult.getValue());
        if (httpResult.getValue() == 200) {
            log.debug("Article send successful, setting status");
        }
        return httpResult;
    }


    public String tidyUrl(String url) {
        return url.replaceAll("(?<!(http:|https:))//","/");
    }
}
