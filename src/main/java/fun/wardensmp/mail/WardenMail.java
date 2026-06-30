package fun.wardensmp.mail;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;
import java.util.*;

public class WardenMail extends JavaPlugin implements Listener, TabCompleter {
    private Connection db;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private static final String PFX = "<aqua><bold>[Почта]</bold></aqua> ";
    private final Random rng = new Random();

    @Override public void onEnable(){
        getDataFolder().mkdirs();
        try {
            Class.forName("org.sqlite.JDBC");
            db = DriverManager.getConnection("jdbc:sqlite:"+new File(getDataFolder(),"mail.db").getPath());
            try(Statement s=db.createStatement()){
                s.executeUpdate("CREATE TABLE IF NOT EXISTS hubs(num TEXT PRIMARY KEY,name TEXT,world TEXT,x INTEGER,y INTEGER,z INTEGER)");
                s.executeUpdate("CREATE TABLE IF NOT EXISTS parcels(track TEXT PRIMARY KEY,sender TEXT,recipient TEXT,origin TEXT,dest TEXT,status TEXT,courier TEXT,created INTEGER,updated INTEGER)");
                s.executeUpdate("CREATE TABLE IF NOT EXISTS roles(player TEXT,role TEXT,PRIMARY KEY(player,role))");
            }
        } catch(Exception e){ getLogger().severe("БД не поднялась: "+e); getServer().getPluginManager().disablePlugin(this); return; }
        try(Statement m=db.createStatement()){ m.executeUpdate("ALTER TABLE parcels ADD COLUMN contents TEXT"); }catch(SQLException ignore){}
        var cmd=getCommand("mail"); cmd.setExecutor(this); cmd.setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this,this);
        getLogger().info("WardenMail включён (SQLite)");
    }
    @Override public void onDisable(){ try{ if(db!=null) db.close(); }catch(Exception ignored){} }

    private void msg(CommandSender s,String m){ s.sendMessage(mm.deserialize(PFX+m)); }
    private void err(CommandSender s,String m){ s.sendMessage(mm.deserialize("<red>[Почта] "+m+"</red>")); }

    @Override public boolean onCommand(CommandSender s,Command c,String l,String[] a){
        if(a.length==0){ help(s); return true; }
        try{
            switch(a[0].toLowerCase()){
                case "register" -> cmdRegister(s,a);
                case "unregister" -> cmdUnregister(s,a);
                case "hubs", "list" -> cmdHubs(s);
                case "send" -> cmdSend(s,a);
                case "track" -> cmdTrack(s,a);
                case "contents" -> cmdContents(s,a);
                case "arrived" -> cmdArrived(s,a);
                case "deliver" -> cmdDeliver(s,a);
                case "mine" -> cmdMine(s);
                case "call" -> cmdCall(s,a);
                case "role" -> cmdRole(s,a);
                default -> help(s);
            }
        } catch(SQLException e){ err(s,"Ошибка БД: "+e.getMessage()); getLogger().warning("SQL: "+e); }
        return true;
    }

    private void cmdRegister(CommandSender s,String[] a) throws SQLException {
        if(!s.hasPermission("mail.admin")){ err(s,"Нет прав (mail.admin)."); return; }
        if(!(s instanceof Player p)){ err(s,"Только из игры."); return; }
        if(a.length<2){ err(s,"Использование: /mail register [номер] <имя> — номер можно опустить, дам следующий"); return; }
        String num; String name;
        if(a[1].matches("\\d+")){ num=a[1]; name=a.length>2?String.join(" ",Arrays.copyOfRange(a,2,a.length)):"Отделение №"+num; }
        else { int next; try(java.sql.ResultSet rr=db.createStatement().executeQuery("SELECT COALESCE(MAX(CAST(num AS INTEGER)),0)+1 FROM hubs")){ rr.next(); next=rr.getInt(1); } num=String.valueOf(next); name=String.join(" ",Arrays.copyOfRange(a,1,a.length)); }
        Location loc=p.getLocation(); int x=loc.getBlockX(),y=loc.getBlockY(),z=loc.getBlockZ(); String w=loc.getWorld().getName();
        if(hubExists(num)){ err(s,"Отделение №"+num+" уже занято! Сначала удали его: /mail unregister "+num); return; }
        if(exists("SELECT 1 FROM hubs WHERE lower(name)=lower(?)",name)){ err(s,"Название «"+name+"» уже занято другим отделением!"); return; }
        try(PreparedStatement ps=db.prepareStatement("INSERT INTO hubs(num,name,world,x,y,z) VALUES(?,?,?,?,?,?)")){
            ps.setString(1,num);ps.setString(2,name);ps.setString(3,w);ps.setInt(4,x);ps.setInt(5,y);ps.setInt(6,z);ps.executeUpdate();
        }
        msg(s,"<green>Отделение №"+num+" зарегистрировано</green> в "+w+" ("+x+", "+y+", "+z+").");
        Component log=mm.deserialize("<red>[Почта-Лог]</red> <yellow>"+p.getName()+"</yellow> зарегистрировал отделение <yellow>№"+num+"</yellow> на X:"+x+" Y:"+y+" Z:"+z+" ("+w+")");
        for(Player ad:Bukkit.getOnlinePlayers()) if(ad.hasPermission("mail.admin")) ad.sendMessage(log);
        getLogger().info("Отделение №"+num+" зарег "+p.getName()+" @ "+w+" "+x+","+y+","+z);
    }
    private void cmdUnregister(CommandSender s,String[] a) throws SQLException {
        if(!s.hasPermission("mail.admin")){ err(s,"Нет прав (mail.admin)."); return; }
        if(a.length<2){ err(s,"Использование: /mail unregister <номер>"); return; }
        try(PreparedStatement ps=db.prepareStatement("DELETE FROM hubs WHERE num=?")){ ps.setString(1,a[1]); if(ps.executeUpdate()==0){ err(s,"Отделение не найдено."); return; } }
        msg(s,"<green>Отделение №"+a[1]+" удалено.</green>");
    }
    private void cmdHubs(CommandSender s) throws SQLException {
        msg(s,"<gold>Почтовые отделения:</gold>");
        try(ResultSet r=db.createStatement().executeQuery("SELECT num,name,world,x,y,z FROM hubs ORDER BY num")){
            boolean any=false;
            while(r.next()){ any=true; s.sendMessage(mm.deserialize(" <yellow>№"+r.getString("num")+"</yellow> <gray>"+r.getString("name")+"</gray> — "+r.getString("world")+" ("+r.getInt("x")+", "+r.getInt("y")+", "+r.getInt("z")+")")); }
            if(!any) s.sendMessage(mm.deserialize(" <gray>пусто</gray>"));
        }
    }
    private void cmdSend(CommandSender s,String[] a) throws SQLException {
        if(!hasRole(s,"assistant")){ err(s,"Эта команда для Ассистентов почты."); return; }
        if(a.length<4){ err(s,"Использование: /mail send <получатель> <отд_отправки> <отд_получения>"); return; }
        String rec=a[1],origin=a[2],dest=a[3];
        if(!hubExists(origin)||!hubExists(dest)){ err(s,"Одно из отделений не зарегистрировано!"); return; }
        String contents=null;
        if(s instanceof Player sp){ contents=readParcel(sp.getInventory().getItemInMainHand()); if(contents==null){ err(s,"Возьми в руку <yellow>посылку</yellow> (предмет или шалкер) — надо зафиксировать содержимое."); return; } }
        String track=genTrack(); long now=System.currentTimeMillis(); String sender=(s instanceof Player p)?p.getName():"Почта";
        try(PreparedStatement ps=db.prepareStatement("INSERT INTO parcels(track,sender,recipient,origin,dest,status,courier,created,updated,contents) VALUES(?,?,?,?,?,'created',NULL,?,?,?)")){
            ps.setString(1,track);ps.setString(2,sender);ps.setString(3,rec);ps.setString(4,origin);ps.setString(5,dest);ps.setLong(6,now);ps.setLong(7,now);ps.setString(8,contents);ps.executeUpdate();
        }
        msg(s,"<green>Посылка принята, содержимое зафиксировано.</green> Трек-номер: <yellow><bold>"+track+"</bold></yellow> — выдай его отправителю.");
        Component call=mm.deserialize(PFX+"<white>Нужен курьер: <yellow>№"+origin+"</yellow> → <yellow>№"+dest+"</yellow>, трек <yellow>"+track+"</yellow>. Оплата по прибытии.</white>");
        for(Player st:Bukkit.getOnlinePlayers()) if(st.hasPermission("mail.staff")) st.sendMessage(call);
        getLogger().info("Посылка "+track+" "+origin+"->"+dest+" получатель "+rec);
    }
    private void cmdTrack(CommandSender s,String[] a) throws SQLException {
        if(a.length<2){ err(s,"Использование: /mail track <номер>"); return; }
        try(PreparedStatement ps=db.prepareStatement("SELECT * FROM parcels WHERE track=?")){
            ps.setString(1,a[1].toUpperCase()); ResultSet r=ps.executeQuery();
            if(!r.next()){ err(s,"Посылка с таким трек-номером не найдена."); return; }
            msg(s,"<gold>Посылка "+r.getString("track")+":</gold>");
            s.sendMessage(mm.deserialize(" <gray>Откуда:</gray> №"+r.getString("origin")+"  <gray>Куда:</gray> №"+r.getString("dest")));
            s.sendMessage(mm.deserialize(" <gray>Получатель:</gray> "+r.getString("recipient")+"  <gray>Статус:</gray> <white>"+statusRu(r.getString("status"))+"</white>"));
        }
    }
    private void cmdArrived(CommandSender s,String[] a) throws SQLException {
        if(!hasRole(s,"courier")){ err(s,"Эта команда для Курьеров почты."); return; }
        if(a.length<2){ err(s,"Использование: /mail arrived <номер>"); return; }
        String track=a[1].toUpperCase(); String rec,dest;
        try(PreparedStatement ps=db.prepareStatement("SELECT recipient,dest FROM parcels WHERE track=?")){
            ps.setString(1,track); ResultSet r=ps.executeQuery(); if(!r.next()){ err(s,"Трек не найден."); return; } rec=r.getString("recipient"); dest=r.getString("dest");
        }
        String courier=(s instanceof Player p)?p.getName():"Почта";
        try(PreparedStatement ps=db.prepareStatement("UPDATE parcels SET status='arrived',courier=?,updated=? WHERE track=?")){ ps.setString(1,courier);ps.setLong(2,System.currentTimeMillis());ps.setString(3,track);ps.executeUpdate(); }
        msg(s,"<green>Посылка "+track+" прибыла в отделение №"+dest+".</green>");
        Player p=null; for(Player o:Bukkit.getOnlinePlayers()) if(o.getName().equalsIgnoreCase(rec)){ p=o; break; }
        if(p!=null){ p.sendMessage(mm.deserialize(PFX+"<white>Привет! Твоя посылка <yellow>"+track+"</yellow> прибыла в <yellow>Отделение №"+dest+"</yellow>! Приходи забирать.</white>"));
            p.sendActionBar(mm.deserialize("<aqua>Посылка "+track+" в отделении №"+dest+"!</aqua>")); }
    }
    private void cmdDeliver(CommandSender s,String[] a) throws SQLException {
        if(!hasRole(s,"assistant")){ err(s,"Эта команда для Ассистентов почты."); return; }
        if(a.length<2){ err(s,"Использование: /mail deliver <номер>"); return; }
        try(PreparedStatement ps=db.prepareStatement("UPDATE parcels SET status='delivered',updated=? WHERE track=?")){ ps.setLong(1,System.currentTimeMillis());ps.setString(2,a[1].toUpperCase()); if(ps.executeUpdate()==0){ err(s,"Трек не найден."); return; } }
        msg(s,"<green>Посылка "+a[1].toUpperCase()+" выдана получателю. Сделка закрыта.</green>");
    }
    private void cmdMine(CommandSender s) throws SQLException {
        if(!(s instanceof Player p)){ err(s,"Только из игры."); return; }
        msg(s,"<gold>Твои посылки:</gold>");
        try(PreparedStatement ps=db.prepareStatement("SELECT track,dest,status FROM parcels WHERE lower(recipient)=lower(?) AND status!='delivered' ORDER BY created DESC")){
            ps.setString(1,p.getName()); ResultSet r=ps.executeQuery(); boolean any=false;
            while(r.next()){ any=true; s.sendMessage(mm.deserialize(" <yellow>"+r.getString("track")+"</yellow> → №"+r.getString("dest")+" <gray>("+statusRu(r.getString("status"))+")</gray>")); }
            if(!any) s.sendMessage(mm.deserialize(" <gray>нет активных посылок</gray>"));
        }
    }
    private void cmdCall(CommandSender s,String[] a) throws SQLException {
        if(!hasRole(s,"courier")){ err(s,"Эта команда для Курьеров почты."); return; }
        if(a.length<3){ err(s,"Использование: /mail call <отд_А> <отд_Б>"); return; }
        if(!hubExists(a[1])||!hubExists(a[2])){ err(s,"Одно из отделений не зарегистрировано!"); return; }
        Component call=mm.deserialize(PFX+"<white>Срочно нужен курьер: <yellow>№"+a[1]+"</yellow> → <yellow>№"+a[2]+"</yellow>!</white>");
        for(Player st:Bukkit.getOnlinePlayers()) if(st.hasPermission("mail.staff")) st.sendMessage(call);
        msg(s,"<green>Курьеры оповещены.</green>");
    }
    @EventHandler public void onJoin(PlayerJoinEvent e){
        Bukkit.getScheduler().runTaskLater(this,()->{
            try(PreparedStatement ps=db.prepareStatement("SELECT COUNT(*) FROM parcels WHERE lower(recipient)=lower(?) AND status='arrived'")){
                ps.setString(1,e.getPlayer().getName()); ResultSet r=ps.executeQuery();
                if(r.next() && r.getInt(1)>0) e.getPlayer().sendMessage(mm.deserialize(PFX+"<white>У тебя <yellow>"+r.getInt(1)+"</yellow> посылок ждут в отделениях! Команда <yellow>/mail mine</yellow>.</white>"));
            } catch(SQLException ex){ getLogger().warning("join notify: "+ex); }
        }, 40L);
    }
    private boolean hubExists(String num) throws SQLException { return exists("SELECT 1 FROM hubs WHERE num=?",num); }
    private boolean exists(String sql,String p) throws SQLException { try(PreparedStatement ps=db.prepareStatement(sql)){ ps.setString(1,p); return ps.executeQuery().next(); } }
    private String genTrack() throws SQLException { String alf="0123456789ABCDEFGHJKLMNPQRSTUVWXYZ"; String t; do{ StringBuilder b=new StringBuilder("WM-"); for(int i=0;i<6;i++) b.append(alf.charAt(rng.nextInt(alf.length()))); t=b.toString(); } while(exists("SELECT 1 FROM parcels WHERE track=?",t)); return t; }
    private String statusRu(String st){ return switch(st){ case "created" -> "Принята, ждёт курьера"; case "in_transit" -> "В пути"; case "arrived" -> "Прибыла в отделение"; case "delivered" -> "Выдана"; default -> st; }; }
    private void help(CommandSender s){ msg(s,"<gold>Команды Единой Почты:</gold>");
        for(String h:new String[]{"<yellow>/mail send <получатель> <отд_А> <отд_Б></yellow> <gray>— принять посылку (сотрудник)</gray>","<yellow>/mail arrived <трек></yellow> <gray>— отметить прибытие (курьер)</gray>","<yellow>/mail deliver <трек></yellow> <gray>— выдать получателю</gray>","<yellow>/mail track <трек></yellow> <gray>— статус посылки</gray>","<yellow>/mail contents <трек></yellow> <gray>— что внутри (персонал)</gray>","<yellow>/mail mine</yellow> <gray>— мои посылки</gray>","<yellow>/mail hubs</yellow> <gray>— список отделений</gray>","<yellow>/mail register [номер] <имя></yellow> <gray>— отделение, номер авто (админ)</gray>","<yellow>/mail unregister <номер></yellow> <gray>— удалить (админ)</gray>","<yellow>/mail role <ник> add <курьер|ассистент></yellow> <gray>— выдать должность (админ)</gray>"})
            s.sendMessage(mm.deserialize("  "+h));
    }
    @Override public List<String> onTabComplete(CommandSender s,Command c,String l,String[] a){
        if(a.length==1){ List<String> o=new ArrayList<>(); for(String x:new String[]{"send","arrived","deliver","track","contents","mine","hubs","register","unregister","call","role"}) if(x.startsWith(a[0].toLowerCase())) o.add(x); return o; }
        return Collections.emptyList();
    }

    private boolean hasRole(CommandSender s,String role){
        if(!(s instanceof Player p)) return true;
        if(p.isOp()||p.hasPermission("mail.admin")) return true;
        try(PreparedStatement ps=db.prepareStatement("SELECT 1 FROM roles WHERE lower(player)=lower(?) AND role=?")){ ps.setString(1,p.getName()); ps.setString(2,role); return ps.executeQuery().next(); } catch(SQLException e){ return false; }
    }
    private String roleKey(String s){ s=s.toLowerCase(); if(s.startsWith("кур")||s.equals("courier")||s.equals("staff")) return "courier"; if(s.startsWith("асс")||s.equals("assistant")||s.equals("deliver")) return "assistant"; return null; }
    private String roleRu(String r){ return r.equals("courier")?"Курьер":r.equals("assistant")?"Ассистент":r; }
    private void cmdRole(CommandSender s,String[] a) throws SQLException {
        if(!s.hasPermission("mail.admin")){ err(s,"Нет прав (mail.admin)."); return; }
        if(a.length<2){ err(s,"Использование: /mail role <ник> <add|remove> <курьер|ассистент>  •  /mail role <ник> — посмотреть"); return; }
        String nick=a[1];
        if(a.length==2){ msg(s,"<gold>Роли игрока "+nick+":</gold>"); boolean any=false;
            try(PreparedStatement ps=db.prepareStatement("SELECT role FROM roles WHERE lower(player)=lower(?)")){ ps.setString(1,nick); ResultSet r=ps.executeQuery(); while(r.next()){ any=true; s.sendMessage(mm.deserialize(" <yellow>"+roleRu(r.getString("role"))+"</yellow>")); } }
            if(!any) s.sendMessage(mm.deserialize(" <gray>нет ролей</gray>")); return; }
        if(a.length<4){ err(s,"Укажи роль: /mail role <ник> "+a[2]+" <курьер|ассистент>"); return; }
        String role=roleKey(a[3]); if(role==null){ err(s,"Неизвестная роль. Доступно: курьер, ассистент."); return; }
        String act=a[2].toLowerCase();
        if(act.equals("add")||act.equals("выдать")){
            try(PreparedStatement ps=db.prepareStatement("INSERT OR IGNORE INTO roles(player,role) VALUES(?,?)")){ ps.setString(1,nick); ps.setString(2,role); ps.executeUpdate(); }
            msg(s,"<green>"+nick+" теперь "+roleRu(role)+".</green>");
            Player t=Bukkit.getPlayerExact(nick); if(t==null) for(Player o:Bukkit.getOnlinePlayers()) if(o.getName().equalsIgnoreCase(nick)){t=o;break;}
            if(t!=null) t.sendMessage(mm.deserialize(PFX+"<white>Тебя назначили на должность: <yellow>"+roleRu(role)+"</yellow> Почты Warden SMP!</white>"));
        } else if(act.equals("remove")||act.equals("снять")||act.equals("del")){
            try(PreparedStatement ps=db.prepareStatement("DELETE FROM roles WHERE lower(player)=lower(?) AND role=?")){ ps.setString(1,nick); ps.setString(2,role); if(ps.executeUpdate()==0){ err(s,"У игрока нет этой роли."); return; } }
            msg(s,"<green>С "+nick+" снята роль "+roleRu(role)+".</green>");
        } else err(s,"Действие: add или remove.");
    }


    private String describe(ItemStack it){
        StringBuilder out=new StringBuilder();
        out.append(it.getType().name()).append(" x").append(it.getAmount());
        var im=it.getItemMeta();
        if(im!=null && im.hasDisplayName()) out.append(" «").append(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(im.displayName())).append("»");
        for(var en: it.getEnchantments().entrySet()) out.append(" {").append(en.getKey().getKey().getKey()).append(en.getValue()).append("}");
        if(im instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta esm) for(var en: esm.getStoredEnchants().entrySet()) out.append(" {").append(en.getKey().getKey().getKey()).append(en.getValue()).append("}");
        return out.toString();
    }
    private String readParcel(ItemStack hand){
        if(hand==null || hand.getType().isAir()) return null;
        if(hand.getType().name().endsWith("SHULKER_BOX") && hand.getItemMeta() instanceof org.bukkit.inventory.meta.BlockStateMeta bsm && bsm.getBlockState() instanceof org.bukkit.block.ShulkerBox sb){
            StringBuilder out=new StringBuilder("Шалкер: "); boolean first=true;
            for(ItemStack it: sb.getInventory().getContents()){ if(it==null||it.getType().isAir()) continue; if(!first) out.append(", "); first=false; out.append(describe(it)); }
            if(first) out.append("пусто");
            return out.toString();
        }
        return describe(hand);
    }
    private void cmdContents(CommandSender s,String[] a) throws SQLException {
        if(!(s.hasPermission("mail.admin") || hasRole(s,"assistant") || hasRole(s,"courier"))){ err(s,"Только для персонала почты."); return; }
        if(a.length<2){ err(s,"Использование: /mail contents <трек>"); return; }
        try(PreparedStatement ps=db.prepareStatement("SELECT contents,recipient FROM parcels WHERE track=?")){
            ps.setString(1,a[1].toUpperCase()); ResultSet r=ps.executeQuery();
            if(!r.next()){ err(s,"Трек не найден."); return; }
            msg(s,"<gold>Содержимое "+a[1].toUpperCase()+" (получатель "+r.getString("recipient")+"):</gold>");
            String cont=r.getString("contents");
            s.sendMessage(Component.text(" "+(cont==null?"не зафиксировано":cont)));
        }
    }

}
