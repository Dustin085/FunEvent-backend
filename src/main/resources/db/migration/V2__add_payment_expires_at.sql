-- Payment 自己的期限，跟 Order 的 payment-timeout 是兩個獨立的時鐘。
-- 逾時取消排程判斷一筆訂單能不能取消時，只要還有一筆 Payment 落在自己的
-- 期限內就不會動它——避免使用者在訂單快過期前才按下付款，卻被訂單剩下
-- 的時間卡住，付完款才發現訂單已經被取消、票已經還回去了。

-- 既有資料補值：用 created_at + 30 分鐘當作合理的估計值。
-- 這些都是舊資料（多半早就是 SUCCESS 或 FAILED），expires_at 補多少
-- 已經不影響任何正在進行中的判斷。
alter table payments add column expires_at timestamp(6) with time zone;

update payments set expires_at = created_at + interval '30 minutes' where expires_at is null;

alter table payments alter column expires_at set not null;
