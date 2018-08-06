package stork.module.sftp;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.ChannelSftp.LsEntry;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.SftpATTRS;
import stork.cred.StorkUserinfo;
import stork.feather.*;
import stork.feather.errors.AuthenticationRequired;
import stork.feather.util.ThreadBell;
import stork.module.Module;

import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.Vector;

/** A module for SFTP/SFTP file transfers. */
public class SFTPModule extends Module<SFTPResource> {
  {
    name("Stork SFTP Module");
    protocols("scp", "sftp");
    description("A module for SFTP/SCP file transfers.");
  }

  public SFTPResource select(URI uri, Credential credential, String id) {
    URI ep = uri.endpointURI();
    return new SFTPSession(ep, credential).select(uri.path(), id);
  }
}

class SFTPSession extends Session<SFTPSession, SFTPResource> {
  transient com.jcraft.jsch.Session jsch;
  transient ChannelSftp channel;

  private transient String host, username, password;
  private transient int port = 22;

  /** Create an SFTPSession. */
  public SFTPSession(URI uri, Credential credential) {
    super(uri, credential);
  }

  /** Get an SFTPResource. */
  public SFTPResource select(Path path, String id) {
    return new SFTPResource(this, path, id);
  }

  /** Connect to the remote server. */
  public Bell<SFTPSession> initialize() {
    host = uri.host();
    if (host == null)
      throw new RuntimeException("No hostname provided.");

    if (uri.port() > 0)
      port = uri.port();

    String[] ui = null;

    if (credential == null)
      ui = uri.userPass();
    else if (credential instanceof StorkUserinfo)
      ui = ((StorkUserinfo) credential).data();
    if (ui == null || ui.length != 2)
      throw new RuntimeException("Invalid credential.");

    username = ui[0];
    password = ui[1];
    if(username == null || password == null)
        throw new AuthenticationRequired("userinfo");
    // Do the actual connection on a new thread.
    return new ThreadBell<Void>() {
      public Void run() throws Exception {
        // Configure JSch to use a real kex algo.
        Properties conf = new Properties();
        conf.put("kex",
          "diffie-hellman-group1-sha1,diffie-hellman-group14-sha1,"+
          "diffie-hellman-group-exchange-sha1,"+
          "diffie-hellman-group-exchange-sha256");
        conf.put("StrictHostKeyChecking", "no");

        jsch = new JSch().getSession(username, host, port);
        jsch.setPassword(password);
        jsch.setConfig(conf);
        jsch.connect(3000);
        channel = (ChannelSftp) jsch.openChannel("sftp");
        channel.connect();
        return null;
      }
    }.start().as(this);
  }

  protected void cleanup() {
    if (jsch != null)
      jsch.disconnect();
    jsch = null;
  }
}

class SFTPResource extends Resource<SFTPSession, SFTPResource> {
  public SFTPResource(SFTPSession session, Path path, String id) {
    super(session, path, id);
  }

  public Bell<Stat> stat() {
    return new ThreadBell<Stat>() {
      public Stat run() throws Exception {
        // First stat the thing to see if it's a directory.
        SftpATTRS attrs = session.channel.stat(path.toString());
        Stat stat = attrsToStat(attrs);
        stat.name = path.name();

        if (stat.file)
          return stat;

        // If it's a directory, list it.
        Vector<LsEntry> v = (Vector<LsEntry>)
          session.channel.ls(path.toString());
        List<Stat> files = new LinkedList<Stat>();

        if (v != null) for (LsEntry e : v) {
          Stat s = attrsToStat(e.getAttrs());
          s.name = e.getFilename();
          files.add(s);
        }

        stat.setFiles(files);
        return stat;
      }
    }.startOn(initialize());
  }

  /** Convert JSch attrs to Feather stat. */
  private Stat attrsToStat(SftpATTRS attrs) {
    Stat stat = new Stat(path.name());
    stat.dir  = attrs.isDir();
    stat.file = !stat.dir;
    if (attrs.isLink())
      stat.link = "(unknown)";
    stat.size = attrs.getSize();
    stat.perm = attrs.getPermissionsString();
    stat.time = attrs.getMTime();
    return stat;
  }

  public Bell mkdir() {
    return new ThreadBell<SFTPResource>() {
      public SFTPResource run() throws Exception {
        session.channel.mkdir(path.toString());

        return session.root();
      }
    }.startOn(initialize());
  }

  //removes file or directory
  public Bell delete() {
    if (!isSingleton())
      throw new UnsupportedOperationException();
    return new ThreadBell<SFTPResource>()  {
      public SFTPResource run() throws Exception {
        if(session.channel.stat(path.toString()).isDir())
            session.channel.rmdir(path.toString());

        else
            session.channel.rm(path.toString());
        return session.root();
      }
    }.startOn(initialize());
  }

  public Tap tap() {
    return new Tap(this) {
      protected Bell start(Bell bell) {
        return new ThreadBell<Void>() {
          public Void run() throws Exception {
            session.channel.get(path.toString(), asOutputStream());
            return null;
          } public void done() {
            finish();
          } public void fail(Throwable t) {
            finish(t);
          }
        }.startOn(initialize().and(bell));
      }
    };
  }

  public Sink sink() {
    return new Sink(this) {
      private java.io.OutputStream os;

      protected Bell start() {
        return initialize().and((Bell<Stat>)source().stat()).new As<Void>() {
          public Void convert(Stat stat) throws Exception {
            os = session.channel.put(path.toString());
            return null;
          } public void fail(Throwable t) {
            finish(t);
          }
        };
      }

      protected Bell drain(final Slice slice) {
        return new ThreadBell<Void>() {
          public Void run() throws Exception {
            os.write(slice.asBytes());
            os.flush();
            return null;
          }
        }.start();
      }

      protected void finish(Throwable t) {
        try {
          os.close();
        } catch (Exception e) {
          // Ignore.
        }
      }
    };
  }
}
