"""More 16x16 rule icons (same palette; generate.py adds the outline via @icon).

Each function draws on a Canvas: rect/line/poly/circle/set with Sweetie-16 color names.
"""
import math


def prayermat(cv):
    cv.rect(3, 1, 12, 14, "teal")
    cv.rect(4, 2, 11, 13, "green")
    cv.poly([(5, 7), (5, 4), (8, 2.5), (11, 4), (11, 7)], "teal")          # arch (prayer niche)
    cv.rect(6, 9, 9, 9, "yellow")
    cv.rect(6, 11, 9, 11, "yellow")
    for x in (3, 5, 7, 9, 11):                                             # fringe
        cv.set(x + 1, 15, "yellow")
    cv.set(8, 5, "yellow")


def mosque(cv):
    cv.rect(2, 9, 13, 14, "white")
    cv.circle(7.5, 8, 4.2, "sky", max_y=8)                                 # dome
    cv.rect(7, 2, 8, 3, "yellow")
    cv.rect(13, 3, 14, 14, "silver")                                       # minaret
    cv.rect(13, 1, 14, 2, "sky")
    cv.rect(6, 11, 9, 14, "navy")                                          # door
    cv.set(7, 10, "navy")
    cv.set(8, 10, "navy")


def beads(cv):
    for i in range(14):
        a = i / 14 * 2 * math.pi
        x, y = 8 + 5 * math.cos(a), 7 + 5 * math.sin(a)
        cv.set(round(x), round(y), "orange" if i % 2 else "yellow")
    cv.rect(7, 12, 8, 14, "orange")
    cv.set(8, 15, "yellow")


def shield(cv):
    cv.poly([(2.5, 2), (13.5, 2), (13.5, 8), (8, 14.5), (2.5, 8)], "blue")
    cv.poly([(4, 3.5), (8, 3.5), (8, 12.5), (4, 7.5)], "sky")
    for dy in (0, 1):
        cv.line(5, 7 + dy, 7, 9 + dy, "white")
        cv.line(7, 9 + dy, 11, 5 + dy, "white")


def brokenshield(cv):
    cv.poly([(2.5, 2), (13.5, 2), (13.5, 8), (8, 14.5), (2.5, 8)], "gray")
    cv.poly([(4, 3.5), (8, 3.5), (8, 12.5), (4, 7.5)], "silver")
    for (x, y) in ((8, 2), (7, 4), (9, 6), (7, 8), (8, 10), (8, 12)):     # crack
        cv.set(x, y, "red")
        cv.set(x, y + 1, "red")


def pickaxe(cv):
    for i in range(10):                                                    # handle
        cv.set(4 + i, 13 - i, "orange" if i % 3 else "red")
        cv.set(5 + i, 13 - i, "orange")
    cv.poly([(2, 5), (6, 1.5), (11, 1.5), (14.5, 5), (11, 3.5), (6, 3.5)], "cyan")
    cv.rect(8, 2, 10, 4, "silver")


def laptop(cv):
    cv.rect(3, 3, 12, 10, "dark")
    cv.rect(4, 4, 11, 9, "navy")
    cv.rect(5, 5, 8, 5, "lime")
    cv.rect(5, 7, 10, 7, "sky")
    cv.rect(1, 11, 14, 12, "silver")
    cv.rect(6, 11, 9, 11, "gray")


def code(cv):
    for i in range(4):                                                     # < and >
        cv.set(4 - i, 7 - i, "lime"); cv.set(4 - i, 8 + i, "lime")
        cv.set(11 + i, 7 - i, "lime"); cv.set(11 + i, 8 + i, "lime")
    for i in range(9):                                                     # /
        cv.set(9 - i // 2, 3 + i, "white")


def rocket(cv):
    cv.poly([(8, 1), (11, 5), (11, 11), (5, 11), (5, 5)], "white")
    cv.rect(5, 5, 6, 10, "silver")
    cv.circle(8, 6.5, 1.6, "sky")
    cv.poly([(5, 8), (2, 12), (5, 11)], "red")
    cv.poly([(11, 8), (14, 12), (11, 11)], "red")
    cv.rect(6, 12, 9, 12, "orange")
    cv.rect(7, 13, 8, 14, "yellow")


def briefcase(cv):
    cv.rect(6, 2, 9, 3, "dark")
    cv.rect(2, 4, 13, 13, "orange")
    cv.rect(2, 4, 13, 5, "red")
    cv.rect(2, 8, 13, 8, "red")
    cv.rect(7, 7, 8, 9, "yellow")


def coins(cv):
    for y in (12, 10, 8):
        cv.rect(2, y, 9, y + 1, "orange")
        cv.rect(2, y, 9, y, "yellow")
    cv.circle(11, 6, 4, "yellow")
    cv.circle(11, 6, 2.6, "orange")
    cv.rect(11, 4, 11, 8, "yellow")


def wallet(cv):
    cv.rect(2, 4, 13, 13, "purple")
    cv.rect(2, 4, 13, 5, "red")
    cv.rect(9, 7, 14, 10, "red")
    cv.rect(11, 8, 12, 9, "yellow")
    cv.rect(4, 2, 10, 4, "lime")                                           # bill sticking out


def cart(cv):
    cv.rect(1, 2, 3, 3, "silver")
    cv.poly([(3, 4), (14.5, 4), (12.5, 10), (5, 10)], "silver")
    cv.rect(5, 5, 12, 8, "orange")
    cv.rect(5, 11, 12, 11, "silver")
    cv.rect(5, 12, 6, 13, "dark")
    cv.rect(11, 12, 12, 13, "dark")


def alarm(cv):
    cv.circle(8, 9, 5.5, "red")
    cv.circle(8, 9, 4, "white")
    cv.rect(8, 6, 8, 9, "dark")
    cv.rect(8, 9, 10, 9, "dark")
    cv.circle(3, 3, 2, "yellow")
    cv.circle(13, 3, 2, "yellow")
    cv.set(4, 14, "dark"); cv.set(12, 14, "dark")


def bed(cv):
    cv.rect(1, 6, 2, 13, "orange")
    cv.rect(3, 9, 14, 11, "white")
    cv.rect(3, 8, 6, 9, "sky")                                             # pillow
    cv.rect(7, 8, 14, 9, "blue")                                           # blanket
    cv.rect(3, 12, 14, 12, "orange")
    cv.rect(14, 12, 14, 13, "orange")
    cv.rect(11, 3, 13, 3, "white"); cv.set(12, 4, "white"); cv.rect(11, 5, 13, 5, "white")   # z


def shirt(cv):
    cv.poly([(5, 2), (11, 2), (15, 5), (13, 8), (12, 7), (12, 14), (4, 14), (4, 7), (3, 8), (1, 5)], "blue")
    cv.poly([(6, 2), (8, 5), (10, 2)], "white")
    cv.rect(8, 5, 8, 13, "navy")
    cv.set(8, 7, "white"); cv.set(8, 10, "white")


def cigarette(cv):
    cv.rect(1, 9, 11, 11, "white")
    cv.rect(11, 9, 14, 11, "orange")
    cv.rect(1, 9, 2, 11, "red")
    cv.set(0, 10, "yellow")
    for (x, y) in ((2, 7), (3, 5), (2, 3), (4, 1)):                        # smoke
        cv.set(x, y, "silver")


def nosmoke(cv):
    cv.rect(3, 7, 12, 9, "white")
    cv.rect(10, 7, 12, 9, "orange")
    cv.circle(8, 8, 7, "red")
    cv.circle(8, 8, 5.4, None)
    cv.rect(4, 7, 11, 9, "white")
    cv.rect(10, 7, 11, 9, "orange")
    for i in range(10):
        cv.set(3 + i, 3 + i, "red")
        cv.set(4 + i, 3 + i, "red")


def salad(cv):
    cv.circle(4, 7, 2.5, "green")
    cv.circle(8, 6, 3, "lime")
    cv.circle(12, 7, 2.5, "green")
    cv.set(6, 7, "red"); cv.set(10, 6, "red"); cv.set(8, 4, "orange")
    cv.poly([(1, 8), (15, 8), (12, 14), (4, 14)], "white")
    cv.rect(4, 13, 11, 13, "silver")


def pizza(cv):
    cv.poly([(2, 3), (14, 3), (8, 15)], "yellow")
    cv.rect(2, 2, 14, 3, "orange")
    cv.circle(6, 6, 1.3, "red")
    cv.circle(10, 7, 1.3, "red")
    cv.circle(8, 10, 1.2, "red")


def owl(cv):
    cv.circle(8, 9, 6, "orange")
    cv.poly([(2.5, 5), (4, 1.5), (6, 4)], "orange")
    cv.poly([(13.5, 5), (12, 1.5), (10, 4)], "orange")
    cv.circle(5.5, 7, 2.2, "white")
    cv.circle(10.5, 7, 2.2, "white")
    cv.set(5, 7, "black"); cv.set(6, 7, "black"); cv.set(10, 7, "black"); cv.set(11, 7, "black")
    cv.set(8, 9, "yellow"); cv.set(8, 10, "yellow")
    cv.rect(5, 12, 11, 13, "yellow")


def tree(cv):
    cv.circle(8, 6, 5.5, "green")
    cv.circle(6, 5, 2.5, "lime")
    cv.rect(7, 11, 8, 15, "orange")


def house(cv):
    cv.poly([(1, 8), (8, 1.5), (15, 8)], "red")
    cv.rect(3, 8, 12, 14, "white")
    cv.rect(7, 10, 9, 14, "orange")
    cv.rect(4, 9, 5, 10, "sky")
    cv.rect(11, 2, 12, 5, "dark")


def couch(cv):
    cv.rect(2, 4, 13, 9, "purple")
    cv.rect(1, 7, 3, 12, "red")
    cv.rect(12, 7, 14, 12, "red")
    cv.rect(3, 9, 12, 11, "red")
    cv.rect(4, 5, 7, 7, "red")
    cv.rect(8, 5, 11, 7, "red")
    cv.set(2, 13, "dark"); cv.set(13, 13, "dark")


def scissors(cv):
    cv.circle(4, 12, 2.4, "red")
    cv.circle(4, 12, 1, None)
    cv.circle(12, 12, 2.4, "red")
    cv.circle(12, 12, 1, None)
    cv.line(6, 10, 12, 1, "silver")
    cv.line(10, 10, 4, 1, "silver")
    cv.line(7, 10, 12, 2, "white")
    cv.line(9, 10, 4, 2, "white")


def broom(cv):
    cv.line(11, 1, 6, 9, "orange")
    cv.line(12, 1, 7, 9, "orange")
    cv.poly([(4, 8), (9, 9), (8, 15), (1, 13)], "yellow")
    for x in (2, 4, 6):
        cv.line(x + 2, 10, x, 14, "orange")


def compass(cv):
    cv.circle(8, 8, 7, "silver")
    cv.circle(8, 8, 5.6, "white")
    cv.poly([(8, 2.5), (9.5, 8), (6.5, 8)], "red")
    cv.poly([(8, 13.5), (9.5, 8), (6.5, 8)], "gray")
    cv.set(8, 8, "dark")


def sparkles(cv):
    def star(cx, cy, r, c):
        cv.rect(cx, cy - r, cx, cy + r, c)
        cv.rect(cx - r, cy, cx + r, cy, c)
        cv.rect(cx - 1, cy - 1, cx + 1, cy + 1, c)
    star(6, 9, 5, "yellow")
    star(12, 4, 3, "white")
    cv.set(13, 12, "cyan"); cv.set(12, 13, "cyan"); cv.set(13, 13, "cyan")


def coffee(cv):
    cv.rect(3, 6, 11, 13, "white")
    cv.rect(4, 7, 10, 8, "orange")
    cv.rect(12, 8, 13, 11, "white")
    cv.set(12, 8, "white")
    cv.rect(2, 14, 12, 14, "silver")
    cv.set(5, 3, "silver"); cv.set(6, 2, "silver"); cv.set(8, 4, "silver"); cv.set(9, 3, "silver")


def tooth(cv):
    cv.poly([(3, 3), (13, 3), (13, 8), (11, 14), (9, 14), (8, 9), (7, 14), (5, 14), (3, 8)], "white")
    cv.circle(5.5, 3.5, 2.5, "white")
    cv.circle(10.5, 3.5, 2.5, "white")
    cv.rect(5, 3, 6, 4, "cyan")


def pill(cv):
    cv.poly([(2, 9), (8, 3), (12, 7), (6, 13)], "white")
    cv.poly([(8, 3), (10, 1.5), (14.5, 6), (12, 7)], "red")
    cv.circle(3.8, 10.8, 2.3, "white")
    cv.circle(12.4, 4.2, 2.3, "red")
    cv.set(9, 4, "orange")


def gamepad(cv):
    cv.rect(2, 5, 13, 11, "dark")
    cv.circle(3.5, 9, 2.5, "dark")
    cv.circle(12.5, 9, 2.5, "dark")
    cv.rect(4, 7, 4, 9, "silver"); cv.rect(3, 8, 5, 8, "silver")
    cv.set(11, 7, "red"); cv.set(12, 8, "lime"); cv.set(10, 8, "sky"); cv.set(11, 9, "yellow")


def guitar(cv):
    cv.circle(5, 11, 3.5, "orange")
    cv.circle(8, 8, 2.5, "orange")
    cv.circle(5, 11, 1.2, "dark")
    for i in range(6):
        cv.set(9 + i, 6 - i, "dark")
    cv.rect(13, 0, 15, 2, "red")


def paw(cv):
    cv.circle(8, 11, 3.5, "orange")
    for (x, y) in ((3, 6), (6, 3), (10, 3), (13, 6)):
        cv.circle(x, y, 1.6, "orange")


def mountain(cv):
    cv.poly([(0, 14), (6, 3), (12, 14)], "gray")
    cv.poly([(6, 14), (11, 6), (16, 14)], "silver")
    cv.poly([(4.5, 6), (6, 3), (7.5, 6)], "white")
    cv.poly([(10, 7.5), (11, 6), (12, 7.5)], "white")
    cv.rect(11, 1, 11, 4, "dark"); cv.rect(12, 1, 14, 2, "red")          # flag


def ball(cv):
    cv.circle(8, 8, 6.5, "white")
    cv.poly([(8, 5), (10.5, 7), (9.5, 10), (6.5, 10), (5.5, 7)], "dark")
    for (x, y) in ((8, 2), (13, 6), (12, 12), (4, 12), (3, 6)):
        cv.set(x, y, "dark")


def brush(cv):
    cv.line(13, 2, 7, 8, "orange")
    cv.line(14, 3, 8, 9, "orange")
    cv.rect(6, 8, 8, 10, "silver")
    cv.poly([(3, 10), (7, 10), (6, 14), (1, 14)], "purple")
    cv.set(2, 13, "red")


def camera(cv):
    cv.rect(1, 5, 14, 13, "dark")
    cv.rect(4, 3, 8, 5, "dark")
    cv.circle(8, 9, 3.2, "silver")
    cv.circle(8, 9, 1.8, "blue")
    cv.set(7, 8, "white")
    cv.rect(12, 6, 13, 6, "yellow")


def globe(cv):
    cv.circle(8, 8, 6.5, "blue")
    cv.poly([(4, 4), (8, 3), (9, 6), (6, 8), (4, 7)], "green")
    cv.poly([(9, 9), (12, 8), (13, 11), (10, 13)], "green")
    cv.set(5, 11, "green"); cv.set(6, 12, "green")


def gift(cv):
    cv.rect(2, 6, 13, 13, "red")
    cv.rect(1, 5, 14, 7, "red")
    cv.rect(7, 5, 8, 13, "yellow")
    cv.rect(1, 6, 14, 6, "yellow")
    cv.circle(5.5, 3.5, 2, "yellow"); cv.circle(10.5, 3.5, 2, "yellow")
    cv.circle(5.5, 3.5, .8, None); cv.circle(10.5, 3.5, .8, None)


def candy(cv):
    cv.circle(8, 8, 4, "red")
    cv.line(6, 6, 10, 10, "white")
    cv.line(5, 8, 8, 11, "white")
    cv.poly([(4, 8), (1, 5), (1, 11)], "orange")
    cv.poly([(12, 8), (15, 5), (15, 11)], "orange")


def soda(cv):
    cv.rect(4, 2, 11, 14, "red")
    cv.rect(4, 2, 11, 2, "silver")
    cv.rect(4, 14, 11, 14, "silver")
    cv.rect(5, 6, 10, 9, "white")
    cv.rect(6, 7, 9, 7, "red")
    cv.rect(5, 3, 5, 13, "orange")


def fire(cv):
    cv.poly([(8, 1), (13, 8), (12, 13), (8, 15), (4, 13), (3, 8), (6, 5), (7, 8)], "red")
    cv.poly([(8, 6), (11, 10), (10, 13), (8, 14), (6, 13), (5, 10)], "orange")
    cv.poly([(8, 9), (9.5, 12), (8, 13.5), (6.5, 12)], "yellow")


def lotus(cv):
    cv.poly([(8, 2), (10.5, 8), (8, 12), (5.5, 8)], "purple")
    cv.poly([(2, 6), (7, 9), (8, 12), (3, 11)], "red")
    cv.poly([(14, 6), (9, 9), (8, 12), (13, 11)], "red")
    cv.rect(2, 12, 14, 13, "green")
    cv.set(8, 6, "orange")


def smile(cv):
    cv.circle(8, 8, 6.5, "yellow")
    cv.rect(5, 5, 6, 7, "dark")
    cv.rect(10, 5, 11, 7, "dark")
    cv.rect(5, 10, 11, 10, "dark")
    cv.set(4, 9, "dark"); cv.set(12, 9, "dark")
    cv.rect(6, 11, 10, 11, "dark")


def piggy(cv):
    cv.circle(8, 9, 5.5, "red")
    cv.rect(12, 7, 15, 10, "red")
    cv.set(14, 8, "purple"); cv.set(14, 9, "purple")
    cv.poly([(4, 4), (6, 2), (7, 5)], "red")
    cv.set(10, 7, "dark")
    cv.rect(5, 4, 8, 4, "dark")                                            # coin slot
    cv.rect(4, 14, 5, 15, "red"); cv.rect(10, 14, 11, 15, "red")
    cv.set(5, 1, "yellow"); cv.set(6, 1, "yellow")


def laundry(cv):
    cv.rect(2, 1, 13, 14, "white")
    cv.rect(2, 1, 13, 3, "silver")
    cv.set(4, 2, "red"); cv.set(6, 2, "lime")
    cv.circle(8, 9, 4, "gray")
    cv.circle(8, 9, 3, "sky")
    cv.rect(6, 10, 10, 11, "blue")


def pan(cv):
    cv.circle(6, 9, 5, "dark")
    cv.circle(6, 9, 3.6, "gray")
    cv.rect(11, 8, 15, 9, "orange")
    cv.circle(6, 9, 1.8, "white")
    cv.circle(6, 9, .9, "yellow")


def wave(cv):
    for y, c in ((6, "sky"), (10, "blue"), (14, "navy")):
        for x in range(16):
            dy = round(1.5 * math.sin(x / 2.2 + y))
            cv.rect(x, y + dy, x, y + dy + 1, c)
    cv.set(5, 4, "white"); cv.set(12, 5, "white")


def openbook(cv):
    cv.poly([(1, 3), (8, 5), (8, 14), (1, 12)], "white")
    cv.poly([(15, 3), (8, 5), (8, 14), (15, 12)], "silver")
    for y in (6, 8, 10):
        cv.line(3, y, 6, y + 1, "gray")
        cv.line(10, y + 1, 13, y, "gray")


def bike(cv):
    cv.circle(4, 11, 3, "dark"); cv.circle(4, 11, 1.8, None)
    cv.circle(12, 11, 3, "dark"); cv.circle(12, 11, 1.8, None)
    cv.line(4, 11, 7, 6, "red"); cv.line(7, 6, 12, 11, "red")
    cv.line(7, 6, 11, 6, "red"); cv.line(11, 6, 12, 11, "red")
    cv.rect(6, 4, 8, 4, "dark")
    cv.rect(11, 4, 12, 5, "silver")


def handshake(cv):
    cv.poly([(1, 6), (6, 5), (10, 9), (7, 12), (1, 10)], "orange")
    cv.poly([(15, 6), (10, 5), (5, 9), (8, 12), (15, 10)], "yellow")
    cv.rect(0, 5, 1, 11, "blue")
    cv.rect(14, 5, 15, 11, "red")


DRAW = [prayermat, mosque, beads, shield, brokenshield, pickaxe, laptop, code, rocket, briefcase, coins, wallet,
        cart, alarm, bed, shirt, cigarette, nosmoke, salad, pizza, owl, tree, house, couch, scissors, broom, compass,
        sparkles, coffee, tooth, pill, gamepad, guitar, paw, mountain, ball, brush, camera, globe, gift, candy, soda,
        fire, lotus, smile, piggy, laundry, pan, wave, openbook, bike, handshake]
