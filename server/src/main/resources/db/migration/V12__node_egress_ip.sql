-- #79 (Locality serving): the node's IP as the Hub sees it at register time
-- (call.request.origin.remoteHost). Egress-IP equality is the same-LAN heuristic:
-- a playback client whose egress IP matches a node's is treated as on that node's LAN,
-- so the title can be served straight from the Node instead of the Hub.
ALTER TABLE nodes ADD COLUMN egress_ip TEXT;
