// Fixed neutral video examples: no external catalog, search data or network.
export const cases = [
  { id:"garden", grades:{ compost:3, seedlings:2, soil:1 } },
  { id:"bread", grades:{ sourdough:3, kneading:2, flour:1 } },
  { id:"astronomy", grades:{ moon:3, planets:2, telescope:1 } },
  { id:"repair", grades:{ bicycle:3, brakes:2, tools:1 } },
];
export const baseline = {
  garden:["soil","moon","compost","seedlings","tools"],
  bread:["flour","bicycle","kneading","sourdough","soil"],
  astronomy:["telescope","tools","moon","planets","flour"],
  repair:["tools","moon","brakes","bicycle","soil"],
};
export const candidate = {
  garden:["compost","seedlings","soil","moon","tools"],
  bread:["sourdough","kneading","flour","bicycle","soil"],
  astronomy:["moon","planets","telescope","tools","flour"],
  repair:["bicycle","brakes","tools","moon","soil"],
};
